package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.skills.Cleric;
import org.gms.constants.skills.Hermit;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/*
 * Targeting and route-dispatch model adapted from GreenCatMS bot combat. Credit: NutNNut.
 * Bot attack driver. Resolves the bot's attacks from its class + weapon (BotAttackConfig),
 * then each tick picks the AoE skill when 2+ mobs are in reach else the
 * single-target one, faces the nearest, rolls fixed per-line damage, and strikes via the
 * profile's route (melee / ranged / magic). Re-attacks are throttled per bot via
 * nextAttackByBot; clearBot() releases a despawned bot's timer. botAttack() is
 * cooldown-gated; forceAttack() ignores it for the !bot attack GM test.
 */
public final class BotAttackDriver {

    // botId -> absolute epoch-ms before which this bot may not swing again.
    private static final Map<Integer, Long> nextAttackByBot = new ConcurrentHashMap<>();

    // Full-map ultimates (Dragon Roar / Genesis / Blizzard / Meteor Shower) clear the entire map in
    // one cast, so a grinding bot shouldn't fire one every swing. On top of the normal per-swing
    // cooldown they carry this much longer, separate cooldown; while it's cooling, AUTO keeps mobbing
    // with the class's sustained AoE (Crusher / Shining Ray / Ice Strike / Explosion) instead of
    // dropping to single-target. ~25s lets a bot use its ultimate roughly twice a minute.
    // botId -> absolute epoch-ms before which this bot's full-map ultimate is unavailable.
    private static final long FULL_MAP_ULTIMATE_COOLDOWN_MS = 25_000;
    private static final Map<Integer, Long> nextUltimateByBot = new ConcurrentHashMap<>();

    // Candidate pre-filter radius (px); the per-profile reach box is the hard gate.
    private static final int SEEK_RANGE = 700;
    private static final double SEEK_RANGE_SQ = (double) SEEK_RANGE * SEEK_RANGE;

    // Reused type filter for the mob scans (read-only in getMapObjectsInRange), so the target
    // search does not allocate a fresh List on every attack tick.
    private static final List<MapObjectType> MONSTER_TYPES = List.of(MapObjectType.MONSTER);

    // A forward (non-surround) attack still reaches a hair behind the bot so a mob right on top
    // of it counts; mobs clearly behind are excluded (e.g. a crossbow won't fire backwards).
    private static final int BACK_MARGIN = 25;
    // Bosses are large and multi-bodied - Zakum's arms sit well above the bot's foothold - so
    // their part positions are tested against a vertically padded copy of the reach box.
    private static final int BOSS_Y_PAD = 300;
    // Two horizontal extents count as "centred on the bot" (a surround attack) when they match
    // within this slack (px).
    private static final int SYMMETRY_SLACK = 24;

    // Cleric Heal (2301002) damages undead as an AoE in v83 (client-authoritative). A cleric-line bot
    // casts it at an undead pack instead of its normal bolt; the MAGIC route renders the heal and applies
    // real damage like any bot magic attack. Heal is not a charge skill, so magicChargeFor(-1) already fits.
    private static final BotAttackProfile HEAL_PROFILE = BotAttackProfile.magicAoe(Cleric.HEAL, 1);

    /*
     * A slot whose attack skill requires the bot's attack-enabler aura (变身 gates Shockwave /
     * 碎石乱击, 超级变身 gates 毁灭炮(金手指) / 潜龙出渊, the 海盗船 gates the ship guns; single
     * source: BotAuraState.enablerFor) resolves to null while that exact aura is down - an
     * untransformed bot simply does not have the skill right now. Null means "this slot is
     * unavailable this attempt"; the AUTO fallbacks and the forced-choice existence checks above
     * turn that into the aura-free attack or an honest miss report.
     */
    private static BotAttackProfile gateProfile(Character bot, BotAttackProfile profile, WeaponType weapon) {
        if (profile == null || !BotAuraState.isAttackEnablerSkill(profile.skillFor(weapon))) {
            return profile;
        }
        int enabler = BotAuraState.enablerSkillFor(bot, profile.skillFor(weapon));
        return (enabler != 0 && BotAuraState.isMorphedAs(bot, enabler)) ? profile : null;
    }

    private BotAttackDriver() {}

    /* Outcome of an attack attempt, for the GM command to report. */
    public record AttackResult(boolean hit, String monsterName, int damage, boolean killed, String reason) {
        static AttackResult hit(String name, int dmg, boolean killed) {
            return new AttackResult(true, name, dmg, killed, null);
        }
        static AttackResult miss(String reason) {
            return new AttackResult(false, null, 0, false, reason);
        }
    }

    /* Which configured attack a swing uses: AUTO smart-picks; SINGLE/AOE/ULTIMATE force one slot. */
    public enum Choice { AUTO, SINGLE, AOE, ULTIMATE }

    /*
     * Cooldown-gated swing at the in-reach mobs (AUTO: AoE when 2+ mobs are in reach, else
     * single). Cheap and safe to call every tick; most ticks do nothing. The FSM entry point.
     */
    public static AttackResult botAttack(Character bot) {
        return attack(bot, false, Choice.AUTO);
    }

    /* Force the SINGLE-target attack now, ignoring cooldown (backs !bot attack <id>). */
    public static AttackResult forceSingle(Character bot) {
        return attack(bot, true, Choice.SINGLE);
    }

    /* Force the sustained AoE attack now, ignoring cooldown (backs !bot attackaoe <id>). */
    public static AttackResult forceAoe(Character bot) {
        return attack(bot, true, Choice.AOE);
    }

    /* Force the full-map ultimate now, ignoring both cooldowns (backs !bot attackult <id>). */
    public static AttackResult forceUltimate(Character bot) {
        return attack(bot, true, Choice.ULTIMATE);
    }

    /* Release a despawned bot's cooldown timers so the maps don't grow unbounded. */
    public static void clearBot(int botId) {
        nextAttackByBot.remove(botId);
        nextUltimateByBot.remove(botId);
    }

    /*
     * The bot's effective forward attack reach in px (its single-target profile's reach, else its AoE's).
     * Roaming callers use this to approach only to within striking distance instead of always closing to
     * melee range - so a ranged/magic bot stops and attacks from afar. 0 if it has no configured attack.
     */
    public static int attackReachX(Character bot) {
        BotAttackProfile p = primaryProfile(bot);
        return p != null ? p.reachX : 0;
    }

    /* The bot's effective vertical attack reach in px; pairs with attackReachX for approach gating. */
    public static int attackReachY(Character bot) {
        BotAttackProfile p = primaryProfile(bot);
        return p != null ? p.reachY : 0;
    }

    private static BotAttackProfile primaryProfile(Character bot) {
        if (bot == null) {
            return null;
        }
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(bot.getJob(), weapon);
        if (atks.single() != null) {
            return atks.single();
        }
        return atks.aoe() != null ? atks.aoe() : atks.ultimate();
    }

    /* True if this bot's class has any configured AoE swing (sustained or ultimate) - roaming callers use
     * it to decide whether to step into the middle of a mob pack before firing (the AoE-reposition flourish). */
    public static boolean hasAoeAttack(Character bot) {
        if (bot == null) {
            return false;
        }
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(bot.getJob(), weapon);
        return atks.aoe() != null || atks.ultimate() != null;
    }

    private static AttackResult attack(Character bot, boolean force, Choice choice) {
        if (bot == null || bot.getMap() == null) {
            return AttackResult.miss("bot or map is null");
        }
        // A mob debuff can disarm the bot: STUN/SEDUCE pin it entirely and SEAL blocks skills, so no
        // swing fires. Forced attacks (the !bot attack GM test) bypass this so a dev can still probe a
        // debuffed bot; organic swings (botAttack) honour it.
        BotDebuffState status = BotDebuffState.of(bot);
        if (!force && status != null && status.blocksAttack()) {
            return AttackResult.miss("debuffed: cannot attack");
        }
        long now = System.currentTimeMillis();
        if (!force && now < nextAttackByBot.getOrDefault(bot.getId(), 0L)) {
            return AttackResult.miss("on cooldown");
        }

        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(bot.getJob(), weapon);
        // Every slot is gated up front, BEFORE the forced-slot existence checks: a skill that
        // requires the 变身 morph / 海盗船 resolves to null while that exact aura is down (a real
        // client refuses it untransformed, and a plain 变身 does not license a 超级变身 skill), so
        // AUTO, `!bot attack` and `!bot attackaoe` all read one consistent picture of "which
        // attacks does this bot have RIGHT NOW". The forced-choice checks below turn a null slot
        // into an honest miss report instead of an illegal cast.
        BotAttackProfile single = gateProfile(bot, atks.single(), weapon);
        BotAttackProfile aoe = gateProfile(bot, atks.aoe(), weapon);
        BotAttackProfile ultimate = gateProfile(bot, atks.ultimate(), weapon); // throttled full-map nuke, or null

        // Forced slots must exist; AUTO needs at least one configured attack.
        if (choice == Choice.SINGLE && single == null) {
            return AttackResult.miss(bot.getJob() + " has no single-target attack"
                    + (atks.single() != null ? " (requires its 变身/海盗船 aura)" : ""));
        }
        if (choice == Choice.AOE && aoe == null) {
            return AttackResult.miss(bot.getJob() + " has no AoE attack"
                    + (atks.aoe() != null ? " (requires its 变身/海盗船 aura)" : ""));
        }
        if (choice == Choice.ULTIMATE && ultimate == null) {
            return AttackResult.miss(bot.getJob() + " has no ultimate attack"
                    + (atks.ultimate() != null ? " (requires its 变身/海盗船 aura)" : ""));
        }
        if (single == null && aoe == null && ultimate == null) {
            return AttackResult.miss("no attack for job " + bot.getJob() + " / weapon " + weapon);
        }

        // Face the nearest mob BEFORE measuring reach, so directional attacks are oriented
        // toward the fight (the reach box is built relative to the bot's facing).
        Monster nearest = nearestMob(bot);
        if (nearest == null) {
            return AttackResult.miss("no targetable mobs within " + SEEK_RANGE + "px (none, or all on a separate ledge)");
        }
        boolean facingLeft = faceTarget(bot, nearest.getPosition());

        // Pick the profile. Forced choices use exactly that slot; AUTO fires an AoE when 2+ mobs are in
        // reach, else the single-target attack.
        BotAttackProfile profile;
        boolean healUndead = false;
        // Set when the AUTO branch below already scanned the pack profile's reach: escalating to that
        // same profile reuses the scan, so the AoE path measures reach once instead of twice.
        List<Monster> reachCache = null;
        if (choice == Choice.SINGLE) {
            profile = single;
        } else if (choice == Choice.AOE) {
            profile = aoe;
        } else if (choice == Choice.ULTIMATE) {
            profile = ultimate;
        } else if (isClericVsUndead(bot, nearest)) {
            profile = HEAL_PROFILE; // Heal-as-damage on the undead pack
            healUndead = true;
        } else {
            // The AoE we'd throw at a pack: the full-map ultimate when it's off its long separate
            // cooldown, otherwise the sustained mob attack (Crusher / Shining Ray / Ice Strike /
            // Explosion). While the ultimate cools, the bot keeps mobbing with the sustained AoE
            // instead of dropping to single-target. Only escalate to an AoE when 2+ mobs are in reach.
            // (Every slot was already enabler-gated above, so a pack attack requiring 变身/海盗船
            // only reaches here while that aura is up.)
            boolean ultReady = ultimate != null && now >= nextUltimateByBot.getOrDefault(bot.getId(), 0L);
            BotAttackProfile packAttack = ultReady ? ultimate : aoe;
            List<Monster> packInReach = packAttack == null
                    ? List.of()
                    : mobsInReach(bot, packAttack, weapon, facingLeft);
            if (packInReach.size() >= 2) {
                profile = packAttack;
                reachCache = packInReach; // same inputs scanned once - reuse for the target list
            } else {
                profile = single != null ? single : (aoe != null ? aoe : ultimate);
            }
        }

        List<Monster> targets = cap(reachCache != null ? reachCache : mobsInReach(bot, profile, weapon, facingLeft),
                profile.numAttacked);
        if (healUndead) {
            targets = undeadOnly(targets); // Heal must not damage a living mob caught in the box
        }
        if (targets.isEmpty()) {
            return AttackResult.miss(healUndead ? "no undead in heal range"
                    : reachDiagnostic(bot, profile, weapon, facingLeft));
        }

        int facingMask = facingLeft ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        int skillId = profile.skillFor(weapon); // sword/axe & spear/pole-arm forms resolve here
        int bodyActionId = BotAttackData.actionFor(skillId, weapon); // skill's own keyframe if it overrides, else weapon's

        // Roll the profile's lines independently per mob; sum a total for the GM report.
        // Shadow Partner mirrors each throw, so the client draws double the stars - match the packet.
        // Per line we roll a crit off the bot's class crit chance: a crit bumps the damage and is
        // encoded NEGATIVE in the packet (BotAttackData.encodeCritLine) so the viewer renders a yellow
        // crit and the mob recoils harder. We sum the real magnitude for the report; BotAttackEffects
        // decodes the lines back before applying HP.
        int linesPerMob = shadowDoubled(bot, profile.numDamage);
        double critChance = BotAttackConfig.critChanceFor(bot.getJob());
        // WEAKEN halves-ish the bot's output; DARKNESS makes the whole swing whiff (every line 0, so
        // the viewer renders a MISS). Both are null-safe no-ops when the bot has no debuffs.
        double outFactor = status != null ? status.outFactor() : 1.0;
        boolean whiff = status != null && status.whiffs();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Map<Monster, List<Integer>> hits = new LinkedHashMap<>();
        int reported = 0;
        for (Monster mob : targets) {
            List<Integer> lines = new ArrayList<>(linesPerMob);
            for (int i = 0; i < linesPerMob; i++) {
                if (whiff) {
                    lines.add(0); // MISS row - no damage applied downstream
                    continue;
                }
                int dmg = profile.rollDamage(bot.getLevel(), bot.getJob());
                if (outFactor < 1.0) {
                    dmg = (int) Math.max(1, Math.round(dmg * outFactor));
                }
                if (rng.nextDouble() < critChance) {
                    dmg = (int) Math.round(dmg * BotAttackConfig.CRIT_MULTIPLIER);
                    lines.add(BotAttackData.encodeCritLine(dmg)); // negative -> client shows a crit
                } else {
                    lines.add(dmg);
                }
                reported += dmg;
            }
            hits.put(mob, lines);
        }

        // An attack is never made from the saddle: drop the mount right before the swing
        // broadcasts (after the "would this actually hit?" gates, so a mount only comes
        // off for a real strike, not every targeting tick).
        soloMapling.ArtificialPlayer.BotMountSystem.BotMount.cancelForAction(bot);
        // 伪装 / 隐身术 break the moment the attack key is pressed (the client's own rule): cancel
        // both hide auras here too, alongside the mount.
        BotAuraState.cancelHidesForAction(bot);

        boolean killed = switch (profile.route) {
            case CLOSE  -> BotAttackEffects.meleeStrike(bot, hits, skillId, profile.skillLevel,
                    bodyActionId, facingMask, profile.speed, profile.hitDelayMs);
            case RANGED -> BotAttackEffects.rangedStrike(bot, hits, skillId, profile.skillLevel,
                    BotAttackData.projectileFor(weapon, bot), bodyActionId, facingMask,
                    BotAttackData.speedFor(weapon, profile.speed), profile.hitDelayMs);
            case MAGIC  -> BotAttackEffects.magicStrike(bot, hits, skillId, profile.skillLevel,
                    bodyActionId, facingMask, profile.speed, profile.hitDelayMs);
        };

        // The 终极攻击 passive: an occasional extra blow right after the swing. A real client rolls
        // the chance and renders the follow-up itself; the bot's roll happens here instead. A whiffed
        // swing (every line a MISS) never triggers it.
        if (!whiff) {
            BotFinalAttack.maybeTrigger(bot, weapon, bodyActionId, facingMask, hits);
        }

        nextAttackByBot.put(bot.getId(), now + profile.cooldownMs);
        if (profile == ultimate) { // firing the throttled full-map nuke starts its long cooldown
            nextUltimateByBot.put(bot.getId(), now + FULL_MAP_ULTIMATE_COOLDOWN_MS);
        }
        String label = targets.size() > 1
                ? targets.size() + " mobs (nearest '" + targets.get(0).getName() + "')"
                : targets.get(0).getName();
        return AttackResult.hit(label, reported, killed);
    }

    /*
     * Double the damage lines while Shadow Partner is up, so the packet's line count matches the
     * mirrored shadow the client draws (Lucky Seven 2->4, Avenger 1->2, Triple Throw 3->6). Gated
     * on the buff being configured for the job (bot self-buffs are only shown, never registered,
     * so getBuffedValue is always null here). Only the claw line carries Shadow Partner.
     */
    private static int shadowDoubled(Character bot, int baseLines) {
        return BotBuffConfig.buffsForJob(bot.getJob()).contains(Hermit.SHADOW_PARTNER)
                ? baseLines * 2 : baseLines;
    }

    // A cleric-line bot (Cleric/Priest/Bishop) whose nearest target is undead: cast Heal-as-damage instead
    // of the normal bolt. Undead is a per-mob WZ flag reached via getStats() (there is no Monster.isUndead).
    private static boolean isClericVsUndead(Character bot, Monster nearest) {
        return nearest != null && bot.getJob() != null && bot.getJob().isA(Job.CLERIC)
                && nearest.getStats() != null && nearest.getStats().isUndead();
    }

    // Keep only the undead mobs — Heal damages undead but would otherwise "heal" a living mob in the box.
    private static List<Monster> undeadOnly(List<Monster> mobs) {
        List<Monster> out = new ArrayList<>(mobs.size());
        for (Monster m : mobs) {
            if (m.getStats() != null && m.getStats().isUndead()) {
                out.add(m);
            }
        }
        return out;
    }

    /* Alive mobs inside the attack's reach box, nearest first. */
    private static List<Monster> mobsInReach(Character bot, BotAttackProfile profile, WeaponType weapon, boolean facingLeft) {
        Point botPos = bot.getPosition();
        if (botPos == null) {
            return List.of();
        }
        Rectangle box = reachBox(bot, profile, weapon, facingLeft);

        List<Monster> found = new ArrayList<>();
        for (MapObject mo : bot.getMap().getMapObjectsInRange(botPos, SEEK_RANGE_SQ, MONSTER_TYPES)) {
            Monster m = (Monster) mo;
            if (!m.isAlive()) {
                continue;
            }
            Point mp = m.getPosition();
            if (mp == null) {
                continue;
            }
            // Bosses are big and multi-bodied: test their part positions against a vertically
            // padded box so e.g. Zakum's elevated arms still register as in-reach.
            Rectangle test = m.isBoss()
                    ? new Rectangle(box.x, box.y - BOSS_Y_PAD, box.width, box.height + 2 * BOSS_Y_PAD)
                    : box;
            // The reach box is a flat rectangle with no terrain sense, so a tall box would let the bot
            // hit a mob standing on a separate platform overhead/below. Drop mobs on a different ledge
            // than the bot (bosses exempt). Cheap (peek-only) and only run for box-passing mobs.
            if (test.contains(mp) && onAttackableSurface(bot.getMap(), botPos, m)) {
                found.add(m);
            }
        }
        found.sort((a, b) -> Double.compare(botPos.distanceSq(a.getPosition()), botPos.distanceSq(b.getPosition())));
        return found;
    }

    /*
     * The attack's effective hit rectangle in map coordinates.
     *
     * Melee uses the skill's real WZ range box when it defines one (correct per-skill size, and the
     * surround-vs-forward shape baked into the game data), otherwise the profile's reach defaults.
     *
     * Ranged and magic attacks are projectiles: the skill's WZ rectangle is only the animation/effect
     * box, not the throw/cast distance - for Lucky Seven and Magic Claw it's authored melee-tight, so a
     * bot using it would only land hits point-blank. For those routes we use the profile's ranged reach
     * so hit detection matches the real attack distance.
     *
     * Forward attacks are clipped to the bot's facing side; surround attacks (a box centred on the
     * character - mage AoE, Dragon Roar, Heaven's Hammer) keep both sides.
     */
    private static Rectangle reachBox(Character bot, BotAttackProfile profile, WeaponType weapon, boolean facingLeft) {
        Point p = bot.getPosition();
        int skillId = profile.skillFor(weapon);
        Rectangle wz = wzBox(skillId, profile.skillLevel, p, facingLeft);

        Rectangle box = (wz != null)
                ? wz
                : new Rectangle(p.x - profile.reachX, p.y - profile.reachY, profile.reachX * 2, profile.reachY * 2);

        // A surround attack's box extends comparably to both sides of the bot. Ranged is never surround
        // (a thrown star is one-directional); centred magic AoE can be.
        boolean surround = wz != null
                && profile.route != BotAttackProfile.Route.RANGED
                && isCentred(box, p.x);

        // Projectiles (ranged/magic): swap the animation-sized WZ box for the profile's true reach so the
        // attack lands at its real distance. Keep the surround shape for centred magic AoE, forward lane otherwise.
        if (profile.route == BotAttackProfile.Route.RANGED || profile.route == BotAttackProfile.Route.MAGIC) {
            box = new Rectangle(p.x - profile.reachX, p.y - profile.reachY,
                    profile.reachX * 2, profile.reachY * 2);
        }

        if (!surround) {
            box = clipForward(box, p.x, facingLeft);
        }
        return box;
    }

    /* The skill's WZ attack rectangle anchored at the bot and flipped for facing, or null. */
    private static Rectangle wzBox(int skillId, int skillLevel, Point from, boolean facingLeft) {
        if (skillId == 0) {
            return null;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return null;
        }
        StatEffect eff = skill.getEffect(Math.max(1, skillLevel));
        return (eff != null) ? eff.getAttackBox(from, facingLeft) : null;
    }

    /* True when the box extends comparably to both sides of the bot (a surround attack). */
    private static boolean isCentred(Rectangle box, int botX) {
        int left = botX - box.x;
        int right = (box.x + box.width) - botX;
        return left > 0 && right > 0 && Math.abs(left - right) <= SYMMETRY_SLACK;
    }

    /* Clip a box to the bot's facing side, keeping a small back margin for adjacency. */
    private static Rectangle clipForward(Rectangle box, int botX, boolean facingLeft) {
        if (facingLeft) {
            int right = Math.min(box.x + box.width, botX + BACK_MARGIN);
            return new Rectangle(box.x, box.y, Math.max(0, right - box.x), box.height);
        }
        int left = Math.max(box.x, botX - BACK_MARGIN);
        return new Rectangle(left, box.y, Math.max(0, (box.x + box.width) - left), box.height);
    }

    /*
     * Orient the bot toward the target before measuring reach and swinging; returns true when it now
     * faces left. GCMovement-driven bots (the roaming grinders) must face through GCMovement.face: that
     * sets the authoritative facingDir and broadcasts the turn, so the bot stays pointed at the mob
     * instead of snapping back to its travel facing the instant the swing ends. The legacy stance-flip
     * only set Character.stance, which GCMovement re-derives from facingDir and overwrites every tick -
     * so a mob behind the bot got a one-frame swing then an immediate revert. Bots not under dynamic
     * control (e.g. the GM TestAttackBot) fall back to that legacy flip.
     */
    private static boolean faceTarget(Character bot, Point target) {
        if (GCMovement.isEnabled(bot)) {
            boolean left = target.x < bot.getPosition().x;
            GCMovement.face(bot, left);
            return left;
        }
        MovementCommands.botFaceTowardsPoint(bot, target);
        return MovementCommands.facingLeft(bot);
    }

    /* The closest live mob within the seek radius (any direction), or null. */
    private static Monster nearestMob(Character bot) {
        Point botPos = bot.getPosition();
        if (botPos == null) {
            return null;
        }
        Monster nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : bot.getMap().getMapObjectsInRange(botPos, SEEK_RANGE_SQ, MONSTER_TYPES)) {
            Monster m = (Monster) mo;
            if (!m.isAlive() || m.getPosition() == null) {
                continue;
            }
            // Skip mobs on a separate ledge so the bot faces (and later swings at) a mob it can
            // actually reach, instead of fixating up at a platform overhead.
            if (!onAttackableSurface(bot.getMap(), botPos, m)) {
                continue;
            }
            double dsq = botPos.distanceSq(m.getPosition());
            if (dsq < bestSq) {
                bestSq = dsq;
                nearest = m;
            }
        }
        return nearest;
    }

    /*
     * A mob on a surface the bot can actually strike: not standing on a separate walkable ledge
     * above or below the bot. Bosses are exempt - large, multi-bodied, with parts on their own
     * footholds (and already handled by the padded reach box). Degrades to "true" (no filtering)
     * when the map's nav graph isn't baked, so it never silently blanks a bot's targets.
     */
    private static boolean onAttackableSurface(MapleMap map, Point botPos, Monster m) {
        if (m.isBoss()) {
            return true;
        }
        Point mp = m.getPosition();
        return mp == null || !GCMovement.onDifferentLedge(map, botPos.x, botPos.y, mp.x, mp.y);
    }

    /* First max of a nearest-first list (the whole list if it's already small enough). */
    private static List<Monster> cap(List<Monster> mobs, int max) {
        return mobs.size() <= max ? mobs : new ArrayList<>(mobs.subList(0, max));
    }

    /*
     * Human-readable hint for the GM command when nothing was in reach: where the nearest mob
     * sits relative to the bot and the reach box it fell outside. Only walked on the miss path.
     */
    private static String reachDiagnostic(Character bot, BotAttackProfile profile, WeaponType weapon, boolean facingLeft) {
        Point botPos = bot.getPosition();
        Monster nearest = nearestMob(bot);
        if (nearest == null) {
            return "no targetable mobs within " + SEEK_RANGE + "px (none, or all on a separate ledge)";
        }
        Rectangle box = reachBox(bot, profile, weapon, facingLeft);
        Point np = nearest.getPosition();
        return "nearest '" + nearest.getName() + "' at dx=" + (np.x - botPos.x) + " dy=" + (np.y - botPos.y)
                + " outside reach x[" + box.x + ".." + (box.x + box.width) + "] y[" + box.y + ".." + (box.y + box.height)
                + "] facing " + (facingLeft ? "left" : "right");
    }
}
