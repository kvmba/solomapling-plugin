package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotHealthFloor;
import soloMapling.companion.CompanionRoster;
import soloMapling.server.MethodScheduler;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

// Makes a bot visibly take damage from mobs - contact/touch damage and fall damage - with organic
// knockback and a broadcast hurt packet. Artificial players lose real server-side HP from mob contact,
// with an MVP 1-HP floor that avoids running the host's client-oriented death flow for a headless
// character. Extracted and trimmed from
// GreenCatMS's BotCombatManager (HP loss,
// death/revive, danger-assessment, and the STANCE knockback roll all removed) and rebound from the
// donor's BotEntry onto this package's BotMovementState. Credit: NutNNut.
//
// Lives in GCMoveSystem (not a sibling package) because it reads/writes package-private
// BotMovementState fields and calls package-private BotPhysicsEngine / BotMovementManager methods -
// the same way the donor kept its combat manager beside the physics it drives.
//
// Scaling: three stacked levers keep this ~free for thousands of bots. (1) The LOD gate - a bot whose
// map has no real player does one cached boolean check and returns. (2) The 1.5s i-frame window after
// each hit. (3) A nearby-only mob query (the swept foot-box, grown by a margin) instead of scanning
// every mob on the map.
//
// Entry points: tickMobDamage (once per bot per tick, from GCMovementDriver) and applyFallDamage
// (from BotPhysicsEngine at the landing transition). Everything else is private.
final class BotContactDamage {

    private static final Logger log = LoggerFactory.getLogger(BotContactDamage.class);
    private static final long DIAGNOSTIC_INTERVAL_MS = 5_000L;
    private static final Map<Integer, Long> NEXT_DIAGNOSTIC_AT = new ConcurrentHashMap<>();

    private BotContactDamage() {
    }

    // Knockback / i-frame tunables (OpenStory Player::damage: hspeed +/-1.5, vforce -= 3.5).
    private static final float KNOCKBACK_HSPEED       = 1.5f;
    private static final float KNOCKBACK_VFORCE       = 3.5f;
    private static final int   MOB_TOUCH_SWEEP_HEIGHT = 50;   // touch box height above the feet
    private static final int   MOB_HIT_COOLDOWN_MS    = 2500; // i-frames after any hit
    // getMapObjectsInRect is point-based (mob anchor must be inside the box), so grow the thin
    // foot-box by this much to catch wide mobs whose anchor sits just outside, then precision-check
    // each candidate with the real lower-half hitbox overlap.
    private static final int   MOB_QUERY_MARGIN       = 150;

    // Damage number is scaled off physical attack. All artificial-player types apply the same roll to
    // real HP through resolveMobHitDamage, so TrainingBot and persistent companion semantics agree.
    private static final double DMG_FACTOR  = 0.5;   // multiplier on mob.getPADamage()
    private static final double DMG_SPREAD  = 0.20;  // +/- random variance around the scaled value

    // A bot already scraping the floor that gets hit again has had its chance to drink: this is
    // the share of those hits that finish it off. The rest leave it pinned at the floor, so a
    // bot under a mob does not die the instant it touches bottom.
    private static final double FLOOR_HIT_DEATH_CHANCE = 0.50;

    // Per-job-family feel (keyed on Job.getJobNiche(): 1=warrior, 4=thief; mage/bowman/pirate/beginner
    // use the base values). Warriors brace against knockback - the damage number still shows, only the
    // recoil is suppressed, and a warrior that does get knocked barely budges. Thieves dodge far more -
    // a higher chance the whole hit is a MISS flash (0 dmg, no recoil). All flat per family for now.
    private static final int    NICHE_WARRIOR     = 1;
    private static final int    NICHE_THIEF       = 4;
    private static final double BASE_MISS_CHANCE  = 0.10; // 0..1 chance a hit is a MISS flash (0 dmg)
    private static final double THIEF_MISS_CHANCE = 0.40;
    private static final double WARRIOR_KB_RESIST = 0.75; // chance a landed hit applies no knockback
    private static final float  WARRIOR_KB_DAMPEN = 0.45f;// recoil impulse scale when a warrior IS knocked

    // Alert/hurt pose (mirrors client set_alerted(5000): absolute reset to now+5s).
    private static final long ALERT_DURATION_MS = 5000L;

    // Fall-damage curve: dmg = SAT*(1-exp(-k*u)) + tail*u, with u = max(0, dist - threshold). Fitted
    // to real-client samples (916->8, 1094->27, 1421->29, 3861->35), all within +/-1 dmg.
    private static final float FALL_DIST_THRESHOLD_PX = 890.0f; // below: 0 dmg, no packet
    private static final float FALL_DMG_SAT           = 28.0f;  // asymptote of the knee component
    private static final float FALL_KNEE_SHARPNESS    = 0.013f; // 1/px - larger = sharper knee
    private static final float FALL_DMG_PER_PX_TAIL   = 0.0024f;// linear tail slope (dmg/px)

    // Per bot per tick: if a hostile mob is touching the bot, apply a hit. LOD-gated (skips entirely
    // when no real player shares the bot's map) and i-frame-gated. Called from GCMovementDriver.tick.
    static void tickMobDamage(BotMovementState entry, Character bot) {
        if (!GCMovement.isMapObserved(bot.getMapId())) {
            entry.mobHitCooldownMs = 0; // reset i-frames so the first hit on re-entry is instant
            diagnose(bot, null, "MAP_UNOBSERVED", null);
            return;
        }
        // A bot already at 0 HP may have been killed by another server subsystem. Do not
        // repeatedly emit touch hits or mutate it here; lifecycle/respawn ownership stays outside
        // this movement layer.
        if (bot.getHp() <= 0) {
            return;
        }
        Point botPos = bot.getPosition();
        try {
            if (entry.mobHitCooldownMs > 0) {
                entry.mobHitCooldownMs = BotMovementManager.tickDown(entry.mobHitCooldownMs);
                return;
            }
            Rectangle query = new Rectangle(getBotTouchBounds(entry, bot));
            query.grow(MOB_QUERY_MARGIN, MOB_QUERY_MARGIN);
            Monster nearby = null;
            for (MapObject obj : bot.getMap().getMapObjectsInRect(query, List.of(MapObjectType.MONSTER))) {
                Monster mob = (Monster) obj;
                if (!isHostileLivingMonster(mob)) {
                    continue;
                }
                nearby = mob;
                if (isMobTouchingBot(entry, bot, mob)) {
                    diagnose(bot, mob, "CONTACT", getBotTouchBounds(entry, bot));
                    applyMobHit(entry, bot, mob);
                    return;
                }
            }
            diagnose(bot, nearby, nearby == null ? "NO_NEARBY_MOB" : "NO_OVERLAP",
                    getBotTouchBounds(entry, bot));
        } finally {
            rememberMobTouchCheck(entry, bot, botPos);
        }
    }

    private static void diagnose(
            Character bot,
            Monster mob,
            String reason,
            Rectangle botBounds) {
        if (bot == null || !CompanionRoster.isCompanion(bot.getId()) || !log.isInfoEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long next = NEXT_DIAGNOSTIC_AT.get(bot.getId());
        if (next != null && next > now) {
            return;
        }
        NEXT_DIAGNOSTIC_AT.put(bot.getId(), now + DIAGNOSTIC_INTERVAL_MS);
        log.info(
                "Companion contact diagnostic cid={} reason={} map={} hp={}/{} botPos={} botBounds={} mobOid={} mobId={} mobPos={} mobBounds={}",
                bot.getId(), reason, bot.getMapId(), bot.getHp(), bot.getMaxHp(),
                bot.getPosition(), botBounds,
                mob == null ? -1 : mob.getObjectId(),
                mob == null ? -1 : mob.getId(),
                mob == null ? null : mob.getPosition(),
                mob == null ? null : BotMobHitboxProvider.getMobBounds(mob));
    }

    // Apply one contact hit from the mob (or a miss flash).
    private static void applyMobHit(BotMovementState entry, Character bot, Monster mob) {
        double missChance = isThief(bot) ? THIEF_MISS_CHANCE : BASE_MISS_CHANCE;
        int dmg = ThreadLocalRandom.current().nextDouble() < missChance ? 0 : rollMobDamage(mob);
        MobHitKnockback kb = resolveMobHitKnockback(bot.getPosition(), mob.getPosition());
        MobHitDamage resolved =
                resolveMobHitDamage(bot.getHp(), dmg, bot.getCurrentMaxHp());
        if (resolved.lethal()) {
            // Beyond saving. Whoever owns this bot's lifecycle takes it from here; if no bot
            // behaviour is driving it, fall through and leave it pinned at the floor rather
            // than at zero with nobody to stand it back up.
            BotDeath owner = BotDeath.of(bot);
            if (owner != null) {
                owner.kill();
            }
            // Still show the number that did it. Deliberately not the shared applyDamage:
            // that also poses the bot as combat-alerted and launches it into the air, and a
            // corpse should do neither.
            bot.getMap().broadcastMessage(bot,
                    PacketCreator.damagePlayer(-1, mob.getId(), bot.getId(), resolved.broadcastDamage(),
                            0, kb.direction(), false, 0, false, 0, 0, 0), false);
            entry.mobHitCooldownMs = BotMovementManager.delayAfterCurrentTick(MOB_HIT_COOLDOWN_MS);
            return;
        }
        if (resolved.hpDamage() > 0) {
            // safeAddHP is the host Character path: it atomically keeps HP above zero, updates the
            // stat, and runs HP-change hooks. The precomputed cap also makes this behavior explicit
            // and testable rather than delegating the MVP floor entirely to host internals.
            bot.safeAddHP(-resolved.hpDamage());
            // The headless BotClient intentionally drops the bot's own STAT_CHANGED packet. Publish
            // party HP directly after the mutation so a real party member can observe the new value;
            // Character.hpChangeAction's deferred map callback is not a reliable UI path here.
            bot.updatePartyMemberHP();
        }
        applyDamage(entry, bot, resolved.broadcastDamage(), -1, mob.getId(),
                kb.direction(), kb.airVelX());
    }

    /**
     * Separates artificial-player HP semantics from the shared visual hit path.
     *
     * <p>The wire damage remains the rolled hit so observers still see the normal hurt effect.
     * HP loss stops at {@link BotHealthFloor} — a sliver of the pool, so a badly hurt bot reads
     * as nearly dead instead of showing an empty bar like a corpse. A hit that would take the
     * bot from above the floor straight through it is {@link #lethal}: there was no chance to
     * drink, so the damage layer does not clamp it and the bot dies.</p>
     */
    static MobHitDamage resolveMobHitDamage(int currentHp, int rolledDamage, int maxHp) {
        int broadcastDamage = Math.max(0, rolledDamage);
        if (broadcastDamage == 0) {
            return new MobHitDamage(broadcastDamage, 0, false);
        }
        if (lethal(currentHp, broadcastDamage, maxHp)) {
            return new MobHitDamage(broadcastDamage, broadcastDamage, true);
        }
        int floor = BotHealthFloor.floorFor(maxHp);
        int hpDamage = Math.min(broadcastDamage, Math.max(0, currentHp - floor));
        return new MobHitDamage(broadcastDamage, hpDamage, false);
    }

    /**
     * Whether a hit is beyond what the bot could have drunk through.
     *
     * <p>Two ways, because the first one alone almost never fires: a bot's pool is
     * {@code 50 + 22*(level-1)} (BotPotionSim), so a level-70 bot has ~1568 HP while contact
     * damage is {@code mob.getPADamage() * 0.5} — a few hundred at most. A single hit that big
     * is exotic, so "one-shot" by itself would make the whole death state dead code.</p>
     *
     * <p>The second way is the ordinary one: the bot was already scraping the floor and this
     * mob hit it again. Half the time that is the end — it had its chance to drink and did not
     * get away.</p>
     */
    static boolean lethal(int currentHp, int rolledDamage, int maxHp) {
        if (rolledDamage <= 0) {
            return false;
        }
        if (rolledDamage >= maxHp) {
            return true; // hit harder than the entire pool — nobody drinks through that
        }
        return BotHealthFloor.atFloor(currentHp, maxHp)
                && ThreadLocalRandom.current().nextDouble() < FLOOR_HIT_DEATH_CHANCE;
    }

    // Lightweight touch-damage roll: no defense math yet; artificial-player HP policy is applied separately.
    private static int rollMobDamage(Monster mob) {
        double base = Math.max(1, mob.getPADamage()) * DMG_FACTOR;
        double spread = 1.0 + ThreadLocalRandom.current().nextDouble(-DMG_SPREAD, DMG_SPREAD);
        return (int) Math.max(1, Math.round(base * spread));
    }

    // Apply fall damage on landing. fallDistancePx is the peak-to-landing descent that
    // BotPhysicsEngine tracks via entry.fallPeakPhysY. LOD- and threshold-gated. Called from
    // BotPhysicsEngine's landing transition.
    static void applyFallDamage(BotMovementState entry, Character bot, float fallDistancePx) {
        if (!GCMovement.isMapObserved(bot.getMapId())) {
            return;
        }
        if (entry.mobHitCooldownMs > 0) {
            return; // damage invincibility window
        }
        int dmg = fallDamageFromDistance(fallDistancePx);
        if (dmg <= 0) {
            return;
        }
        int dirSign = entry.facingDir >= 0 ? 1 : -1;
        int airVelX = Math.round(-dirSign * scaledOpenStoryStep(KNOCKBACK_HSPEED));
        applyDamage(entry, bot, dmg, -3, 0, 0, airVelX);
    }

    static int fallDamageFromDistance(float distancePx) {
        if (distancePx <= FALL_DIST_THRESHOLD_PX) {
            return 0;
        }
        double u = distancePx - FALL_DIST_THRESHOLD_PX;
        double dmg = FALL_DMG_SAT * (1.0 - Math.exp(-FALL_KNEE_SHARPNESS * u))
                + FALL_DMG_PER_PX_TAIL * u;
        return (int) Math.max(1, Math.round(dmg));
    }

    // Core: broadcast the DAMAGE_PLAYER hurt packet, start the i-frame cooldown, set the alert pose,
    // and apply knockback. Shared by mob-touch (damageFrom=-1) and fall (damageFrom=-3). No HP loss
    // and no death - bots stay alive; this renders the hit only. A dmg <= 0 call is a MISS flash
    // (broadcast, no recoil).
    private static void applyDamage(BotMovementState entry, Character bot, int dmg,
                                    int damageFrom, int monsterId,
                                    int broadcastDirection, int knockbackAirVelX) {
        Point botPos = bot.getPosition();

        bot.getMap().broadcastMessage(bot,
                PacketCreator.damagePlayer(damageFrom, monsterId, bot.getId(), Math.max(0, dmg), 0,
                        broadcastDirection, false, 0, false, 0, 0, 0), false);

        entry.mobHitCooldownMs = BotMovementManager.delayAfterCurrentTick(MOB_HIT_COOLDOWN_MS);
        markAlerted(entry);

        if (dmg <= 0 || !shouldApplyMobKnockback(entry, bot)) {
            return; // miss flash, climbing, or a warrior who braced - no recoil
        }
        clearActionState(entry); // cancel current walk/nav so the recoil reads cleanly
        float dampen = isWarrior(bot) ? WARRIOR_KB_DAMPEN : 1.0f; // a warrior that does get knocked barely budges
        int hVel = Math.round(knockbackAirVelX * dampen);
        if (entry.inAir) {
            BotPhysicsEngine.applyAirKnockback(entry, bot, hVel);
        } else {
            BotPhysicsEngine.beginKnockback(entry, bot, botPos,
                    -scaledOpenStoryStep(KNOCKBACK_VFORCE) * dampen, hVel);
        }
        BotMovementManager.broadcastMovement(entry);
    }

    // Knockback applies on any real (non-zero) hit unless the bot is climbing. The donor also rolled
    // against the STANCE buff, but bot self-buffs are shown-not-registered in SoloMapling so STANCE
    // is unreadable here - dropped.
    private static boolean shouldApplyMobKnockback(BotMovementState entry, Character bot) {
        if (entry.climbing) {
            return false;
        }
        // Warriors significantly resist knockback (the damage number still broadcasts from applyDamage;
        // only the recoil is suppressed). Thieves are handled earlier as a higher miss chance, and a
        // miss never reaches here; mage / bowman / pirate / beginner are standard.
        if (isWarrior(bot) && ThreadLocalRandom.current().nextDouble() < WARRIOR_KB_RESIST) {
            return false;
        }
        return true;
    }

    private static boolean isWarrior(Character bot) {
        return bot != null && bot.getJob() != null && bot.getJob().getJobNiche() == NICHE_WARRIOR;
    }

    private static boolean isThief(Character bot) {
        return bot != null && bot.getJob() != null && bot.getJob().getJobNiche() == NICHE_THIEF;
    }

    private static MobHitKnockback resolveMobHitKnockback(Point botPos, Point attackOrigin) {
        boolean attackFromRight = attackOrigin.x > botPos.x;
        int direction = attackFromRight ? 0 : 1;
        int airVelX = Math.round((attackFromRight ? -1f : 1f) * scaledOpenStoryStep(KNOCKBACK_HSPEED));
        return new MobHitKnockback(direction, airVelX);
    }

    private static float scaledOpenStoryStep(float openStoryStepValue) {
        return openStoryStepValue * (BotPhysicsEngine.cfg.TICK_MS / 8.0f);
    }

    // Cancel the current walk/nav action so a knockback recoil isn't immediately overridden.
    private static void clearActionState(BotMovementState entry) {
        entry.attackCooldownMs = 0;
        BotMovementManager.clearNavigationState(entry);
        entry.movementBroadcastValid = false;
    }

    private record MobHitKnockback(int direction, int airVelX) {
    }

    record MobHitDamage(int broadcastDamage, int hpDamage, boolean lethal) {
    }

    // Swept-AABB touch detection (anti-tunnel; lower-half mob box only).
    private static boolean isMobTouchingBot(BotMovementState entry, Character bot, Monster mob) {
        Rectangle botBounds = getBotTouchBounds(entry, bot);
        Rectangle mobBounds = BotMobHitboxProvider.getMobBounds(mob);
        if (mobBounds == null) {
            return false;
        }
        // Only the lower half of the mob deals touch damage (v83 y grows downward, so the bottom is
        // the max-y side). Lets the bot's feet slip under a tall mob's upper body without a hit.
        int lowerHeight = Math.max(1, mobBounds.height / 2);
        Rectangle mobLowerHalf = new Rectangle(mobBounds.x, mobBounds.y + mobBounds.height - lowerHeight,
                mobBounds.width, lowerHeight);
        return mobLowerHalf.intersects(botBounds);
    }

    private static Rectangle getBotTouchBounds(BotMovementState entry, Character bot) {
        Point currentPos = bot.getPosition();
        Point previousPos = currentPos;
        if (entry != null
                && entry.lastMobTouchCheckPos != null
                && entry.lastMobTouchMapId == bot.getMapId()) {
            previousPos = entry.lastMobTouchCheckPos;
        }

        // Mirror the client touch check: sweep the foot position between ticks and use a fixed
        // height above the feet instead of the full character sprite.
        int left = Math.min(previousPos.x, currentPos.x);
        int right = Math.max(previousPos.x, currentPos.x);
        int top = Math.min(previousPos.y, currentPos.y) - MOB_TOUCH_SWEEP_HEIGHT;
        int bottom = Math.max(previousPos.y, currentPos.y);
        return inclusiveRectangle(left, top, right, bottom);
    }

    private static Rectangle inclusiveRectangle(int left, int top, int right, int bottom) {
        return new Rectangle(left, top, Math.max(1, right - left + 1), Math.max(1, bottom - top + 1));
    }

    private static void rememberMobTouchCheck(BotMovementState entry, Character bot, Point position) {
        if (entry == null || bot == null || position == null) {
            return;
        }

        entry.lastMobTouchCheckPos = new Point(position);
        entry.lastMobTouchMapId = bot.getMapId();
    }

    // A living, hostile monster - alive and not a friendly (escort/PQ) mob.
    private static boolean isHostileLivingMonster(Monster monster) {
        return monster != null
                && monster.isAlive()
                && (monster.getStats() == null || !monster.getStats().isFriendly());
    }

    // Package-visible so the attack layer can flag the pose after a swing (via GCMovement.markAlerted),
    // not just mob-touch/fall damage. Absolute reset to now+5s; the reset task keeps the wire stance honest.
    static void markAlerted(BotMovementState entry) {
        entry.alertedUntilMs = System.currentTimeMillis() + ALERT_DURATION_MS;
        scheduleAlertReset(entry);
    }

    // Ensure the bot broadcasts a fresh STAND packet when the alert timer expires, even if it stopped
    // moving meanwhile (otherwise the last-sent ALERT wire stance sticks). Self-reschedules if
    // markAlerted extended the deadline while waiting.
    private static void scheduleAlertReset(BotMovementState entry) {
        if (entry.alertResetScheduled) {
            return;
        }
        entry.alertResetScheduled = true;
        long delay = Math.max(50L, entry.alertedUntilMs - System.currentTimeMillis() + 100L);
        MethodScheduler.runAfterDelay(() -> {
            long now = System.currentTimeMillis();
            if (now < entry.alertedUntilMs) {
                entry.alertResetScheduled = false;
                scheduleAlertReset(entry);
                return;
            }
            entry.alertResetScheduled = false;
            try {
                if (entry.bot != null) {
                    entry.bot.broadcastStance();
                }
            } catch (Throwable ignored) {
            }
        }, delay);
    }
}
