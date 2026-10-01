package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
import org.gms.constants.skills.Aran;
import org.gms.constants.skills.Beginner;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Buccaneer;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.Crusader;
import org.gms.constants.skills.Crossbowman;
import org.gms.constants.skills.DawnWarrior;
import org.gms.constants.skills.Hermit;
import org.gms.constants.skills.Hunter;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.NightWalker;
import org.gms.constants.skills.Noblesse;
import org.gms.constants.skills.Paladin;
import org.gms.constants.skills.Pirate;
import org.gms.constants.skills.Rogue;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.constants.skills.WhiteKnight;
import org.gms.constants.skills.WindArcher;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifetime bookkeeping for the two bot cosmetic auras whose official behaviour is state-bound rather
 * than simply duration-bound, so a plain "show the aura" broadcast is not enough:
 *
 * <ul>
 *   <li><b>Pirate 疾驰 (DASH, e.g. 5001005).</b> The speed/jump burst a real pirate double-taps
 *       into on a committed run. The burst lasts its WZ duration (20s at max level) and rides
 *       through jumps and ropes, but is released the moment the bot STOPS moving on the ground —
 *       the same tick, so {@link BotDashBurst} is the single lifetime authority. The aura here is
 *       its visible side: the movement tick shows the aura while a burst is live and cancels it
 *       the moment the burst is gone (a mount or a live 变身/海盗船 pose still owns the body and
 *       suppresses the display). The bot's kit is resolved from its job's buff registry
 *       ({@link BotBuffConfig}) once and cached, so a bot with a 疾驰 is recognised even before it
 *       ever bursts, and {@link BotBuffDriver} skips 疾驰 in its periodic sweep so the aura never
 *       lingers from a macro cast (a GM cast via {@link BotBuffEffects} reaches
 *       {@link BotDashBurst#startBurst} and runs the real burst).</li>
 *   <li><b>Pirate 橡木伪装 (OAK_BARREL, 5101007).</b> A hide morph. In the official client the attack
 *       key's handler cancels it before doing anything else ({@code if (IsHideMorphed())
 *       SendSkillCancelRequest(BRAWLER_OAK_BARREL)}), and taking a 骑宠 mount clears it. A bot has no
 *       client to run either rule, so {@link #cancelHidesForAction} runs at every swing site and
 *       the movement tick retires it while a mount is up.</li>
 *   <li><b>Pirate 变身 morphs (TRANSFORMATION 5111005 / SUPER_TRANSFORMATION 5121003 / the Cygnus
 *       TRANSFORMATION).</b> Attackable attack-enabler morphs: while the morph is up the player may
 *       use the skills that REQUIRE it (Shockwave / Demolition / Dragon Strike), and the morph's own
 *       pose forbids everything else that owns the body — a 骑宠 mount and 疾驰. Each actual show
 *       (the buff sweep's re-cast, a GM cast) arms the expiry clock via {@link #onAuraShown}, and
 *       {@link #tickMovement} enforces the exclusions while it holds. On expiry the MORPH aura's
 *       cancel goes out the way the host does it and the mount / 疾驰 are free to return; the attack
 *       driver gates the morph-gated skills on {@link #isMorphedAs} meanwhile.</li>
 *   <li><b>Corsair 海盗船 (BATTLE_SHIP, 5221006).</b> The gunner's attack-enabler: Battleship
 *       Cannon / Torpedo are illegal off the ship, and the ship's pose forbids the 骑宠 mount and
 *       疾驰 exactly like a morph. The host registers it as a MONSTER_RIDING buff whose riding item
 *       is forced to the Battleship (1932000), but the plugin only broadcasts the observer frame
 *       ({@code showMonsterRiding}) - the buff itself is never registered, so the 骑宠 mount system
 *       cannot see it and the bookkeeping here ({@link #isMorphedAs}) is the sole source of truth.
 *       Expiry sends the MONSTER_RIDING-bit foreign-buff cancel so every observer's ship model is
 *       cleared with the body swap; the 骑宠 mount and 疾驰 are free to return.</li>
 *   <li><b>Thief 隐身术 (DARK_SIGHT, 4001003 / 14001003).</b> The rogue hide: the official client
 *       renders it as a semi-transparent shade that players STILL SEE (unlike GM hide, the sprite
 *       stays on the map), and while it holds the character cannot be attacked by monsters at all.
 *       The same two cancel rules apply — an attack drops it (the host's own damage handlers cancel
 *       the DARKSIGHT statup on any swing) and a mount clears it — so it shares the 橡木伪装 rules
 *       and the two together form the hide family this class tracks. While a hide aura is up, the
 *       contact-damage layer consults {@link #isMonsterImmune} and skips the bot entirely: no touch
 *       hit, no mob debuff, no fall damage, no touch retaliation.</li>
 * </ul>
 *
 * <p>The 变身 (TRANSFORMATION / SUPER_TRANSFORMATION) morphs are ATTACKABLE, so - unlike the hides -
 * an attack must NOT cancel them. Tracking is therefore keyed on which MORPH skill is currently
 * shown: only when that is 橡木伪装 does {@link #cancelHidesForAction} fire.</p>
 *
 * <p>A bot's auras are broadcast only - no host effect is registered - so the cancel is the matching
 * foreign-buff cancel frame, and "is it up" lives here. Keyed by character id; released on despawn via
 * {@link #clearBot}. Fields are concurrent because the aura broadcast runs on the bot's macro thread
 * while the movement tick runs on the movement thread.</p>
 */
public final class BotAuraState {

    /** The wire statups of a 疾驰 aura, in the host's own DASH2+DASH order (the cancel mask needs both). */
    private static final List<BuffStat> DASH_STATS = List.of(BuffStat.DASH2, BuffStat.DASH);
    /** The wire statup of any skill morph (MORPH). */
    private static final List<BuffStat> MORPH_STATS = List.of(BuffStat.MORPH);
    /** The wire statup of a 隐身术 aura's CANCEL frame (the show frame carries the same DARKSIGHT bit). */
    private static final List<BuffStat> DARK_SIGHT_STATS = List.of(BuffStat.DARKSIGHT);
    /** The cancel mask of a 海盗船 aura (its observer frame is the MONSTER_RIDING mount frame). */
    private static final List<BuffStat> RIDING_STATS = List.of(BuffStat.MONSTER_RIDING);

    /** Fallback re-show cadence for a 疾驰 whose WZ duration is missing (matches BotBuffDriver). */
    private static final long DASH_FALLBACK_REFRESH_MS = 60_000L;

    /** botId -> the 疾驰 skill id this bot's kit carries (0 = none), resolved once and cached. */
    private static final Map<Integer, Integer> DASH_SKILL = new ConcurrentHashMap<>();
    /** botIds whose 疾驰 aura is intended to be up (walking); a cancel is only sent on the drop edge. */
    private static final Set<Integer> DASH_UP = ConcurrentHashMap.newKeySet();
    /** botId -> epoch-ms at which the 疾驰 aura must be re-shown (the WZ duration, minus a margin). */
    private static final Map<Integer, Long> DASH_RESHOW_AT = new ConcurrentHashMap<>();

    /** botId -> the MORPH skill id currently shown (a transformation, or the 伪装 hide morph). */
    private static final Map<Integer, Integer> MORPH_SKILL = new ConcurrentHashMap<>();
    /** botIds whose 隐身术 aura is intended to be up (a cancel is only sent on the drop edge). */
    private static final Set<Integer> DARK_SIGHT_UP = ConcurrentHashMap.newKeySet();

    /**
     * botId -> every plain cosmetic aura currently shown (the non-state-bound buffs whose foreign
     * frame rides {@code giveForeignBuff}: Maple Warrior, Stance, Sharp Eyes, ...). The hide /
     * dash / attack-enabler auras are state-bound and live in their own structures above, and the
     * combo orb ring is owned EXCLUSIVELY by {@link BotComboOrb} - its wire value is the live orb
     * count, so its id is kept OUT of this ledger and out of every generic packet builder (a
     * Crusader job's periodic buff sweep does show 1111002, but {@code onAuraShown} routes it to
     * {@code BotComboOrb.onAttackLanded}'s ring, never here). This is the "which visuals are on
     * this bot" ledger the on-arrival replay consults - the host answers the same question per
     * observer by re-reading the buff registry inside every spawn packet ({@code
     * writeForeignBuffs}); a bot registers nothing there, so the ledger stands in for it.
     * ADD-only (the set dedupes re-shows): the generic foreign frame carries no duration, so a
     * client renders such an aura until a matching cancelForeignBuff - which the plugin never
     * sends for these - so an entry, once shown, is what every observer present keeps rendering.
     */
    private static final Map<Integer, Set<Integer>> SHOWN_AURAS = new ConcurrentHashMap<>();

    /** Expiry fallback for an attack-enabler aura whose WZ duration is missing (the WZ minimum is ~30s). */
    private static final long ENABLER_FALLBACK_MS = 80_000L;

    /**
     * botId -> epoch-ms at which the bot's attack-enabler aura (变身 morph / 海盗船) expires. These
     * are the auras whose presence GATES attacks (Shockwave / Demolition / the Battleship guns) and
     * whose pose excludes the 骑宠 mount and 疾驰; see {@link #isMorphed}.
     */
    private static final Map<Integer, Long> ATTACK_ENABLER_UNTIL = new ConcurrentHashMap<>();

    private BotAuraState() {}

    /**
     * The enabler aura {@code bot} needs for {@code attackSkillId}, or 0 if that skill has no
     * enabler. A bot's kit can only carry the enablers of its own lineage, so if the mapped skill
     * is not in the bot's buff registry the skill is simply not fireable for this bot.
     */
    public static int enablerSkillFor(Character bot, int attackSkillId) {
        int wanted = enablerFor(attackSkillId);
        if (wanted == 0 || bot == null) {
            return 0;
        }
        return BotBuffConfig.buffsForJob(bot.getJob()).contains(wanted) ? wanted : 0;
    }

    /** The host's own {@code isDash} set: the 疾驰 speed/jump burst. */
    public static boolean isDash(int skillId) {
        return skillId == Pirate.DASH || skillId == ThunderBreaker.DASH
                || skillId == Beginner.SPACE_DASH || skillId == Noblesse.SPACE_DASH;
    }

    /** 橡木伪装 (5101007) - the HIDE morph the attack key cancels. */
    static boolean isDisguise(int skillId) {
        return skillId == Brawler.OAK_BARREL;
    }

    /**
     * 隐身术 (4001003 / 14001003) - the rogue-line hide. Mirrors the host's own {@code isDs()} id
     * set: while it holds the character is semi-transparent to players and unattackable by monsters.
     */
    static boolean isDarkSight(int skillId) {
        return skillId == Rogue.DARK_SIGHT || skillId == NightWalker.DARK_SIGHT;
    }

    /**
     * The hide family - 橡木伪装 plus 隐身术. These are the two auras an attack and a mount both
     * cancel, and the two that make the bot unattackable while shown ({@link #isMonsterImmune}).
     */
    static boolean isHide(int skillId) {
        return isDisguise(skillId) || isDarkSight(skillId);
    }

    /**
     * The host's own {@code isSoulArrow()} set: 灵魂箭 (Hunter / Crossbowman / Wind Archer).
     * The host broadcasts this family's foreign frame with the statup PINNED to 0 - a flag, never
     * the WZ x.
     */
    public static boolean isSoulArrow(int skillId) {
        return skillId == Hunter.SOUL_ARROW || skillId == Crossbowman.SOUL_ARROW
                || skillId == WindArcher.SOUL_ARROW;
    }

    /** The host's own {@code isShadowPartner()} set: 影子替身 (Hermit / Night Walker). */
    public static boolean isShadowPartner(int skillId) {
        return skillId == Hermit.SHADOW_PARTNER || skillId == NightWalker.SHADOW_PARTNER;
    }

    /**
     * The host's own {@code isWw()} set: 风灵漫步 (Wind Archer) - also a pin-0 foreign flag.
     */
    public static boolean isWw(int skillId) {
        return skillId == WindArcher.WIND_WALK;
    }

    /**
     * The host's own {@code isInfusion()} id set: 极速领域 (5121009 / 15111005 / 5221010 — the
     * last one the host names {@code Corsair.HEROS_WILL}).
     */
    public static boolean isInfusion(int skillId) {
        return skillId == Buccaneer.SPEED_INFUSION || skillId == ThunderBreaker.SPEED_INFUSION
                || skillId == Corsair.HEROS_WILL;
    }

    /**
     * The host's {@code WK_CHARGE} family by skill id (the 烈焰/寒冰/雷电/圣灵之剑 charges, plus
     * Aran's 雪冲锋 which the host's statup switch also routes to WK_CHARGE). The host's own
     * predicate scans the statup list; by id it is exactly these skills.
     */
    public static boolean isWkCharge(int skillId) {
        return switch (skillId) {
            case WhiteKnight.BW_FIRE_CHARGE, WhiteKnight.BW_ICE_CHARGE, WhiteKnight.BW_LIT_CHARGE,
                 WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.SWORD_LIT_CHARGE,
                 Paladin.BW_HOLY_CHARGE, Paladin.SWORD_HOLY_CHARGE,
                 DawnWarrior.SOUL_CHARGE, ThunderBreaker.LIGHTNING_CHARGE,
                 Aran.SNOW_CHARGE -> true;
            default -> false;
        };
    }

    /**
     * The foreign-aura whitelist, straight from the host's {@code StatEffect.applyBuffEffect}:
     * the ONLY skill families the host ever answers with a {@code GIVE_FOREIGN_BUFF}. A real
     * player casting anything OUTSIDE this set produces no foreign frame at all (the switch
     * leaves {@code mbuff} null), and the client never sees such a buff bit from a legitimate
     * cast - so broadcasting one for a bot is undefined data. Everything the plugin broadcasts
     * as a persistent aura must route through this set (the extended frames - pirate
     * dash/infusion, WK charge, the MONSTER_RIDING mount frame - are inside it; 愤怒 Enrage is
     * deliberately NOT: the host sends no foreign frame for it either).
     */
    public static boolean isForeignAura(int skillId) {
        return isDash(skillId) || isInfusion(skillId) || isWkCharge(skillId) || isDarkSight(skillId)
                || isWw(skillId) || isShadowPartner(skillId) || isSoulArrow(skillId)
                || isTransformMorph(skillId) || isDisguise(skillId) || skillId == Corsair.BATTLE_SHIP
                || skillId == Crusader.COMBO;
    }

    /**
     * The 变身 morph family - ATTACKABLE, so an attack must not cancel them. Mirrors the host's
     * {@code isSkillMorph()} (the explorers the bot registry grades into).
     */
    static boolean isTransformMorph(int skillId) {
        return skillId == Marauder.TRANSFORMATION || skillId == Buccaneer.SUPER_TRANSFORMATION
                || skillId == ThunderBreaker.TRANSFORMATION;
    }

    /**
     * The walk-stance rule, as a pure seam: the WALK stance is exactly the grounded-and-moving
     * pose, so standing (STAND), a jump (JUMP), a swim (SWIM) or a rope/ladder (ROPE/LADDER) all
     * render a different stance. Kept as the classification of "is this stance a walk" — the
     * 疾驰 aura itself now keys on the BotDashBurst buff window, not the stance.
     */
    static boolean dashHolds(int stance) {
        return CharacterStance.isWalking(stance);
    }

    /** The attack-enabler family: the skills whose pose excludes a 骑宠 mount and 疾驰. */
    public static boolean isAttackEnabler(int skillId) {
        return isTransformMorph(skillId) || skillId == Corsair.BATTLE_SHIP;
    }

    /** The attack skills that REQUIRE the attack-enabler aura (the guns, Shockwave, Demolition, ...). */
    public static boolean isAttackEnablerSkill(int skillId) {
        return enablerFor(skillId) != 0;
    }

    /**
     * The attack-enabler aura that gates {@code attackSkillId}, or 0 if it is aura-free. The WZ
     * skill texts name these outright: Shockwave (碎石乱击) "Requires Transformation or Super
     * Transformation"; Demolition (金手指) / Snatch "Can only be used during Super Transformation";
     * the ship guns (急速射 5221007 / 重量炮击 5221008) "Can only be used aboard Battleship";
     * Dragon Strike's (潜龙出渊) dragon-rise pose only exists on the transformed body. Barrage
     * (光速拳, the single slot) is deliberately NOT gated: its desc carries no transform clause,
     * the normal body 00002000 ships the {@code fist} keyframe so the swing renders untransformed,
     * and Super Transformation's 120 s duration / 430 s cooldown would otherwise leave a 4th-job
     * brawler without a single usable attack for ~72% of the time. One place, so a driver fallback
     * and any show always agree on which aura a skill belongs to.
     */
    public static int enablerFor(int attackSkillId) {
        return switch (attackSkillId) {
            case Marauder.SHOCKWAVE -> Marauder.TRANSFORMATION;
            case ThunderBreaker.SHOCK_WAVE -> ThunderBreaker.TRANSFORMATION;
            case Buccaneer.DEMOLITION, Buccaneer.DRAGON_STRIKE -> Buccaneer.SUPER_TRANSFORMATION;
            case Corsair.BATTLESHIP_CANNON, Corsair.BATTLESHIP_TORPEDO -> Corsair.BATTLE_SHIP;
            default -> 0;
        };
    }

    /**
     * True while the bot's attack-enabler aura (变身 morph, or the gunner's 海盗船) is shown. While
     * it holds, {@link BotMount} refuses to mount the bot and the movement tick keeps 疾驰 down;
     * the attack driver only fires the skills that require it once this is true.
     */
    public static boolean isMorphed(Character bot) {
        if (bot == null) {
            return false;
        }
        Long until = ATTACK_ENABLER_UNTIL.get(bot.getId());
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * True while {@code bot} is morphed AS {@code enablerSkillId} specifically (变身 5111005 vs
     * 超级变身 5121003 vs 海盗船 5221006 gate different skills). The attack gate: a Demolition is
     * not covered by a plain 变身 any more than an untransformed swing is.
     */
    public static boolean isMorphedAs(Character bot, int enablerSkillId) {
        if (bot == null || enablerSkillId == 0) {
            return false;
        }
        Long until = ATTACK_ENABLER_UNTIL.get(bot.getId());
        return until != null && System.currentTimeMillis() < until
                && MORPH_SKILL.get(bot.getId()) == enablerSkillId;
    }

    /**
     * Record that {@code bot}'s aura for {@code skillId} was just broadcast (a macro cast or a GM /
     * party-buff show). 疾驰 is included so a stray cast from a stand is retired on the next movement
     * tick; the walking display itself is governed by {@link #tickMovement}. The hide auras are noted
     * so an attack / mount can cancel them (a transform supersedes an earlier disguise).
     */
    public static void onAuraShown(Character bot, int skillId) {
        if (bot == null) {
            return;
        }
        int id = bot.getId();
        if (isDash(skillId)) {
            DASH_SKILL.putIfAbsent(id, skillId);
            DASH_UP.add(id);
            // A GM / party-buff show carries the real burst too, from the caller's thread.
            BotDashBurst.startBurst(bot, skillId);
        } else if (isAttackEnabler(skillId)) {
            // An attack-enabler morph: note which MORPH visual is up (so an expiry swap can name the
            // exact cancel frame) and when it expires. A later enabler overwrites the earlier one -
            // the swap below broadcasts that cancellation - exactly how the client's single MORPH
            // stat slot behaves (a disguise it supersedes leaves the slot with it).
            MORPH_SKILL.put(id, skillId);
            // The level-scaled durationOf: for the 海盗船 the WZ time is a ~24-day mount-style
            // ride - the clamp is what makes the ship an attack-enabler aura a bot actually
            // cycles, instead of a one-cast-per-bot-session pose.
            int durationMs = BotBuffEffects.durationOf(skillId, bot);
            long life = durationMs > 0 ? (long) (durationMs * 0.9) : ENABLER_FALLBACK_MS;
            ATTACK_ENABLER_UNTIL.put(id, System.currentTimeMillis() + life);
        } else if (isDisguise(skillId)) {
            // The disguise rides ONLY the MORPH slot - never the SHOWN_AURAS ledger. The slot is
            // the disguise's single live holder (its cancel, mount teardown and the
            // isAuraLiveGiven replay rule all read it), and adding the id here as well would put
            // it twice into visibleAurasFor's list (slot + ledger) - a doubled MORPH frame to
            // every arriving observer.
            MORPH_SKILL.put(id, skillId);
        } else if (isDarkSight(skillId)) {
            DARK_SIGHT_UP.add(id);
        } else if (skillId == Crusader.COMBO) {
            // The combo ring is BotComboOrb's exclusive wire format (the live count draws the
            // ring, not the WZ statup) - the aura this skill shows IS the ring, so it must never
            // also land in the plain-aura ledger: the replay would send the ring twice, and a
            // ring that already lapsed would render as a phantom from the ledger entry.
            // Its look is (re)drawn by onAttackLanded's broadcasts; nothing to record here.
        } else if (isForeignAura(skillId)) {
            // 灵魂箭 / 影子替身 / 风灵漫步 (and any other generic-frame whitelist family that
            // has no dedicated ledger above): a plain cosmetic aura. The generic giveForeignBuff
            // frame carries no duration, so the client renders such an aura until a matching
            // cancelForeignBuff arrives - and the plugin never sends one for these (no cancel
            // site outside the state-bound frames), it only re-shows them on the buff cadence.
            // The ledger therefore mirrors what an observer is actually rendering: ADD on each
            // show (a re-show re-adds the same id - the set dedupes), nothing is ever retired.
            SHOWN_AURAS.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(skillId);
        }
        // Anything else (the plain numeric buffs - 魔法盾/圣甲术/枫叶勇士/稳如泰山/...): the
        // host broadcasts NO foreign frame for them (StatEffect.applyBuffEffect leaves mbuff
        // null), so BotBuffEffects.auraPacket builds none either. Recording one here would put
        // a frame the client has never legitimately received into the on-arrival replay - the
        // exact "数据非法" source - so non-whitelist ids are deliberately NOT tracked.
    }

    /**
     * Retire / refresh the state-bound auras this tick, from the bot's settled wire stance and mount
     * state. Runs for every GC-controlled bot.
     *
     * <p>The SHOW follows the rest of the plugin's LOD policy (an aura is only worth broadcasting
     * where a real player can see it), but the CANCEL is always sent: a bot's aura has no server-side
     * holder, so once shown it keeps rendering on every client until it receives the cancel -
     * suppressing the cancel because the map just went dark (or the observer poll's cached set
     * flipped) would strand a stale aura. A cancel for an aura that was never shown is a harmless
     * no-op on the client, and the edge is rare, so this costs nothing.</p>
     */
    public static void tickMovement(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int id = bot.getId();
        int dashSkill = dashSkillFor(bot); // cached (0 = no dash)
        boolean hasHide = MORPH_SKILL.containsKey(id) || DARK_SIGHT_UP.contains(id);
        if (dashSkill == 0 && !hasHide) {
            return; // no state-bound aura for this bot (the overwhelming majority)
        }
        boolean observed = GCMovement.isMapObserved(bot.getMapId());
        boolean mounted = bot.getBuffedValue(BuffStat.MONSTER_RIDING) != null;

        // 变身 / 海盗船: the attack-enabler pose owns the body and its expiry is enforced here.
        // On expiry the visuals swap off the way the host's own cancel does: a 变身 morph's foreign
        // representation is the MORPH statup, so its cancel is the MORPH-cancel frame; the 海盗船
        // was shown with the MONSTER_RIDING mount frame (showMonsterRiding), so its expiry needs
        // that bit cleared too or every observer keeps rendering the ship forever.
        Long enablerUntil = ATTACK_ENABLER_UNTIL.remove(id);
        if (enablerUntil != null) {
            if (System.currentTimeMillis() < enablerUntil) {
                ATTACK_ENABLER_UNTIL.put(id, enablerUntil); // still up - put it back for the next tick
            } else {
                Integer shown = MORPH_SKILL.get(id);
                MORPH_SKILL.remove(id);
                if (shown != null && shown == Corsair.BATTLE_SHIP) {
                    cancel(bot, RIDING_STATS);
                } else if (shown != null) {
                    cancel(bot, MORPH_STATS);
                }
            }
        }

        // 疾驰: the aura is the visible side of the BotDashBurst buff — it shows whenever the
        // burst is live and the bot is not mounted, and is cancelled the moment the burst is
        // gone (expiry, or the bot STOPPED moving — BotDashBurst releases it on the stop edge,
        // one tick before this reads it). While bursting it is (re)shown, throttled to the
        // aura's own refresh window; the refresh clock only advances on an actual show, so a
        // burst that begins while unobserved shows on the first observed tick. A live 变身 /
        // 海盗船 never reaches the show branch — its pose gate in BotDashBurst refuses new
        // rolls, and an enabler granted BEFORE a burst expires by its own clock below without
        // tearing the burst down (the host's buff slots are independent).
        if (dashSkill != 0) {
            boolean poseOwned = mounted || isMorphed(bot);
            if (!poseOwned && BotDashBurst.isActive(bot)) {
                boolean firstWalk = DASH_UP.add(id);
                if (observed && (firstWalk || System.currentTimeMillis() >= DASH_RESHOW_AT.getOrDefault(id, 0L))) {
                    int durationMs = BotBuffEffects.showAura(bot, dashSkill);
                    long window = durationMs > 0 ? (long) (durationMs * 0.9) : DASH_FALLBACK_REFRESH_MS;
                    DASH_RESHOW_AT.put(id, System.currentTimeMillis() + window);
                }
            } else if (DASH_UP.remove(id)) {
                DASH_RESHOW_AT.remove(id);
                cancel(bot, DASH_STATS); // always: a shown aura must be retirable whatever the LOD state
            }
        }

        // 伪装 / 隐身: a 骑宠 (mount) clears either hide, exactly as dismounting for an action does.
        // An attack-enabler morph / the Battleship is the same in reverse - the pose owns the body -
        // but it expires by its own clock above and the mount system never mounts a morphed bot
        // (BotMount.tick), so no teardown is needed here.
        if (mounted) {
            if (isDisguised(id)) {
                MORPH_SKILL.remove(id);
                cancel(bot, MORPH_STATS);
            }
            if (DARK_SIGHT_UP.remove(id)) {
                cancel(bot, DARK_SIGHT_STATS);
            }
        }
    }

    /**
     * Cancel every hide aura for an action that officially breaks it: any attack or attack skill (the
     * client drops 橡木伪装 the moment the attack key is pressed, and the host's damage handlers drop
     * 隐身术 on any swing). Called alongside the mount's own {@code cancelForAction} at every swing
     * site. No-op unless a hide is currently shown, so an attackable 变身 is left alone.
     */
    public static void cancelHidesForAction(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int id = bot.getId();
        if (isDisguised(id)) {
            MORPH_SKILL.remove(id);
            cancel(bot, MORPH_STATS);
        }
        if (DARK_SIGHT_UP.remove(id)) {
            cancel(bot, DARK_SIGHT_STATS);
        }
    }

    /**
     * True while a hide aura (橡木伪装 / 隐身术) is shown on this bot: monsters cannot recognise it,
     * so it cannot be attacked. The contact-damage layer (BotContactDamage) consults this and skips
     * the bot entirely - no touch hit, no mob debuff, no fall damage, no touch retaliation. The
     * attackable 变身 morphs do NOT count: they never protect the bot.
     */
    public static boolean isMonsterImmune(Character bot) {
        if (bot == null) {
            return false;
        }
        int id = bot.getId();
        return DARK_SIGHT_UP.contains(id) || isDisguised(id);
    }

    /** This bot's 疾驰 skill id (0 = none), resolved once from its job's buff registry and cached. */
    static int dashSkillFor(Character bot) {
        Integer cached = DASH_SKILL.get(bot.getId());
        if (cached != null) {
            return cached;
        }
        int found = 0;
        for (int skillId : BotBuffConfig.buffsForJob(bot.getJob())) {
            if (isDash(skillId)) {
                found = skillId;
                break;
            }
        }
        DASH_SKILL.put(bot.getId(), found);
        return found;
    }

    private static boolean isDisguised(int botId) {
        Integer shown = MORPH_SKILL.get(botId);
        return shown != null && shown == Brawler.OAK_BARREL;
    }

    /**
     * Whether {@code skillId}'s aura is still actually shown on {@code bot} - the liveness check
     * the on-arrival replay runs at send time. A snapshot taken when the entering player's replay
     * was scheduled can go stale before the send (an attack-enabler expiring on a movement tick, a
     * hide torn off by a swing), and replaying a stale entry would draw a phantom aura nobody
     * else sees. The state-bound auras are checked against their own structures; any other id
     * counts as live (the ledger never retires plain auras - see {@link #onAuraShown}).
     */
    public static boolean isAuraLive(Character bot, int skillId) {
        if (bot == null) {
            return false;
        }
        int id = bot.getId();
        Integer morphShown = MORPH_SKILL.get(id);
        Long until = ATTACK_ENABLER_UNTIL.get(id);
        boolean enablerClockLive = until != null && System.currentTimeMillis() < until;
        return isAuraLiveGiven(skillId, morphShown, enablerClockLive,
                DARK_SIGHT_UP.contains(id), DASH_UP.contains(id), DASH_SKILL.getOrDefault(id, 0));
    }

    /**
     * Pure form of {@link #isAuraLive}, so the replay's liveness rules are testable without a
     * character: {@code morphShown} is the MORPH slot's occupant (null = empty),
     * {@code enablerClockLive} whether the attack-enabler expiry clock still holds, the rest the
     * hide / dash bookkeeping. The dispatch mirrors what each family's own tick enforces.
     */
    static boolean isAuraLiveGiven(int skillId, Integer morphShown, boolean enablerClockLive,
                                   boolean darkSightUp, boolean dashUp, int dashSkill) {
        if (isDarkSight(skillId)) {
            return darkSightUp;
        }
        if (isAttackEnabler(skillId)) {
            // the expiry clock AND the slot still naming this exact enabler (isMorphedAs's rule)
            return enablerClockLive && morphShown != null && morphShown == skillId;
        }
        if (skillId == Brawler.OAK_BARREL) {
            // the disguise rides the MORPH slot and is only torn off by an attack / a mount
            return morphShown != null && morphShown == Brawler.OAK_BARREL;
        }
        if (isDash(skillId)) {
            return dashUp && dashSkill == skillId;
        }
        return true;
    }

    private static void cancel(Character bot, List<BuffStat> statups) {
        bot.getMap().broadcastMessage(bot, PacketCreator.cancelForeignBuff(bot.getId(), statups), false);
    }

    /**
     * The aura ids an arriving observer must be shown so this bot looks to them exactly as it
     * looks to everyone already watching: the state-bound auras that are actually up right now
     * (the attack-enabler 变身/海盗船 by its clock, the hides, the walking 疾驰) plus every plain
     * cosmetic aura in the ledger. The host answers this per observer by re-reading the character's
     * buff registry inside each spawn packet ({@code writeForeignBuffs} - MORPH / DARKSIGHT / COMBO
     * / SOULARROW / the dash bits / MONSTER_RIDING all ride SPAWN_PLAYER); a bot registers no buff,
     * so this snapshot is the plugin's stand-in for that read.
     *
     * <p>Every entry is liveness-checked against the host client at send time (the map call in
     * {@link BotBuffEffects#replayAurasTo}), so a stale entry degrades to a skipped replay, never
     * a phantom aura. Order is irrelevant - each id renders as an independent aura.</p>
     *
     * @return a fresh mutable list, possibly empty; never null. The single-target 变身 gating the
     *         attack skills and the 海盗船 are the ids that keep the "transformed but firing
     *         Shockwave / Battleship Cannon" mismatch from re-appearing on a fresh observer.
     */
    public static List<Integer> visibleAurasFor(Character bot) {
        List<Integer> result = new ArrayList<>();
        if (bot == null) {
            return result;
        }
        int id = bot.getId();
        Integer morph = MORPH_SKILL.get(id);
        if (morph != null) {
            result.add(morph); // 变身 / 海盗船 / 伪装 - liveness checked at send time
        }
        Set<Integer> plain = SHOWN_AURAS.get(id);
        if (plain != null && !plain.isEmpty()) {
            result.addAll(plain);
        }
        // The combo orb ring: its wire value is the live count, not the WZ statup, so the id rides
        // the list only as a marker - the replay's send branch for it goes through BotComboOrb
        // (liveness + the current count), never the generic packet builder.
        if (BotComboOrb.ringShown(bot)) {
            result.add(Crusader.COMBO);
        }
        if (DARK_SIGHT_UP.contains(id)) {
            result.add(Rogue.DARK_SIGHT);
        }
        if (DASH_UP.contains(id)) {
            Integer dashSkill = DASH_SKILL.get(id);
            if (dashSkill != null && dashSkill != 0) {
                result.add(dashSkill);
            }
        }
        return result;
    }

    /** Release a despawned bot's aura bookkeeping so the maps don't grow unbounded. */
    public static void clearBot(int botId) {
        DASH_SKILL.remove(botId);
        DASH_UP.remove(botId);
        DASH_RESHOW_AT.remove(botId);
        MORPH_SKILL.remove(botId);
        DARK_SIGHT_UP.remove(botId);
        SHOWN_AURAS.remove(botId);
        ATTACK_ENABLER_UNTIL.remove(botId);
        BotDashBurst.clearBot(botId);
    }
}
