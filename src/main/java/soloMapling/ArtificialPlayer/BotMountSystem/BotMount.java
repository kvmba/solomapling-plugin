package soloMapling.ArtificialPlayer.BotMountSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Mount;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.Beginner;
import org.gms.net.server.Server;
import org.gms.server.StatEffect;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 坐骑系统 (mount / Monster Riding) for artificial players.
 *
 * <p>A real player rides by casting {@code Beginner.MONSTER_RIDER} (skill 1004),
 * which the host turns into a {@link BuffStat#MONSTER_RIDING} buff plus a
 * {@link Mount} object. Every viewer renders the mount purely from those two:
 * {@code PacketCreator.spawnPlayerMapObject} / {@code writeForeignBuffs} read
 * {@code getBuffedValue(MONSTER_RIDING)} and {@code getMapleMount()} and write the
 * mount item id straight into the look packet. A bot therefore mounts by doing
 * exactly what the player path does — set the {@code Mount} object, register the
 * rider buff, and broadcast {@code showMonsterRiding} once.
 *
 * <p>Rules (product):
 * <ul>
 *   <li>Only bots at {@link #MIN_LEVEL} (70) or above ever ride — the real v83
 *       mount requirement (Hog/Frog etc. carry {@code reqLevel=70}).</li>
 *   <li>Only an {@value #OWN_CHANCE} fraction of the eligible population owns a
 *       mount, decided DETERMINISTICALLY from the bot's character id — so a
 *       persistent companion keeps the same mount across restarts with no extra
 *       storage, and the crowd has a mount minority rather than everyone on a Hog.</li>
 *   <li>A bot is astride only while WALKING or STANDING STILL on solid ground.
 *       Casting a buff, swinging an attack, sitting a chair, climbing/swimming/
 *       falling all dismount it first — the client would otherwise render the
 *       wrong pose and the riding model would fight the skill animation.</li>
 *   <li>Mounts are cosmetic on bots: no real speed change, no fatigue drain.
 *       They exist so the world reads like a populated server.</li>
 * </ul>
 *
 * <p>Applies to every bot family through the one {@link soloMapling.ArtificialPlayer.BotSM}
 * tick: 站街 (SocialBot), 打怪 (TrainingBot), 游走 (TownWandererBot) and 持久化
 * (CompanionBot). {@link #tick(Character)} reconciles the mount with the current pose
 * every tick (cheap no-op for a non-owner) and {@link #cancelForAction(Character)} is
 * the eager fail-safe the skill / attack / chair paths call before changing the pose.
 */
public final class BotMount {

    /** v83's own mount requirement — the Hog & friends sit at reqLevel 70+. */
    public static final int MIN_LEVEL = 70;

    /** Share of eligible (level >= 70) bots that own a mount. */
    public static final double OWN_CHANCE = 0.35;

    /**
     * Mount item ids that are safe to put on a v83 bot: farm mounts whose
     * {@code Character.wz/TamingMob} entries carry real stand/walk/jump frames and
     * a {@code reqLevel} at or below the level floor. Deliberately a fixed
     * allow-list — an item id the client's TamingMob data lacks is a crash for
     * every viewer, so we never roll the whole 1902xxx range.
     *
     * <p>Hog 1902000 / Mushroom 1902008 / Toad 1902009 / Pink Bear 1902012 all
     * carry reqLevel 70, so any level-70 bot can wear them without the host's
     * {@code canWearEquipment} filter dropping the piece from the look packet.
     */
    private static final int[] MOUNT_IDS = {1902000, 1902008, 1902009, 1902012};

    /** Rider skill id: {@code sourceid % 10000000 == 1004} → {@code isMonsterRiding()}. */
    private static final int RIDE_SKILL = Beginner.MONSTER_RIDER;

    /**
     * After an action forces a dismount (a skill, an attack, a chair), the bot will
     * not remount for this long. Without it a grinding bot — which alternates walk
     * and swing every couple of seconds — would flicker on and off the mount. Long
     * enough to ride out a fight, short enough that a bot settling to a stand or a
     * walk after the fight climbs back on.
     */
    private static final long REMOUNT_COOLDOWN_MS = 8_000L;

    // botId -> epoch-ms before which this bot must not remount (set by cancelForAction).
    private static final Map<Integer, Long> remountBlockedUntil = new ConcurrentHashMap<>();

    private BotMount() {}

    /** Whether this bot owns a mount at all, decided stably from its character id. */
    public static boolean ownsMount(Character bot) {
        return bot != null && ownsMountForId(bot.getId());
    }

    /** The mount item id this bot owns (only meaningful when {@link #ownsMount}). */
    private static int ownedMountId(Character bot) {
        return mountItemForId(bot.getId());
    }

    // Pure, deterministic id → decision helpers (split out so the roll is unit-testable
    // without a live Character). ~OWN_CHANCE of the id space maps to "owns", and the
    // chosen mount is spread across the allow-list.
    static boolean ownsMountForId(int cid) {
        return Math.floorMod(mix(cid), 1000) < (int) Math.round(OWN_CHANCE * 1000);
    }

    static int mountItemForId(int cid) {
        return MOUNT_IDS[Math.floorMod(mix(cid), MOUNT_IDS.length)];
    }

    /** Whether the bot is currently astride (has the riding buff registered). */
    public static boolean isRiding(Character bot) {
        return bot != null && bot.getBuffedValue(BuffStat.MONSTER_RIDING) != null;
    }

    /**
     * Reconcile the mount against the bot's present pose. Safe to call every tick:
     * <ul>
     *   <li>owns no mount / below level / map forbids mounts → ensure dismounted;</li>
     *   <li>walking or standing still (and on solid ground, off cooldown) → mount;</li>
     *   <li>any other pose (skill pose, air, rope, swim, chair, death) → dismount.</li>
     * </ul>
     * The skill / attack / chair paths also call {@link #cancelForAction} eagerly; this
     * tick sweep is the backstop.
     */
    public static void tick(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        if (bot.getLevel() < MIN_LEVEL || !ownsMount(bot) || mapForbidsMounts(bot)) {
            if (isRiding(bot)) {
                dismount(bot);
            }
            return;
        }
        if (isMountPose(bot)) {
            if (!isRiding(bot) && System.currentTimeMillis() >= remountAllowedAt(bot)) {
                mount(bot, ownedMountId(bot));
            }
        } else if (isRiding(bot)) {
            dismount(bot);
        }
    }

    /**
     * Eager fail-safe for any action that changes the bot's pose to something a
     * mount can't coexist with (a buff cast, an attack swing, a chair sit). Dismounts
     * and arms the remount cooldown so a walk/swing cycle doesn't flicker the mount.
     * Cheap no-op when the bot isn't riding.
     */
    public static void cancelForAction(Character bot) {
        if (bot == null || (!ownsMount(bot) && !isRiding(bot))) {
            return; // nothing to cancel and it can never mount — skip the bookkeeping write
        }
        remountBlockedUntil.put(bot.getId(), System.currentTimeMillis() + REMOUNT_COOLDOWN_MS);
        if (isRiding(bot)) {
            dismount(bot);
        }
    }

    /** Force-mount for the GM test command, ignoring the ownership/pose/cooldown gates. */
    public static boolean forceMount(Character bot) {
        if (bot == null || bot.getMap() == null || mapForbidsMounts(bot)) {
            return false;
        }
        mount(bot, ownsMount(bot) ? ownedMountId(bot) : MOUNT_IDS[0]);
        return true;
    }

    /** Force-dismount for the GM test command; holds it off like any action does. */
    public static void forceDismount(Character bot) {
        cancelForAction(bot);
    }

    /** Release a despawned bot's cooldown bookkeeping so the maps don't grow unbounded. */
    public static void forget(int botId) {
        remountBlockedUntil.remove(botId);
    }

    /** The mount item ids a GM may hand out (for the command's help text). */
    public static int[] mountIds() {
        return MOUNT_IDS.clone();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static long remountAllowedAt(Character bot) {
        return remountBlockedUntil.getOrDefault(bot.getId(), 0L);
    }

    /** A cheap deterministic integer hash of the character id (stable across restarts). */
    private static int mix(int cid) {
        int h = cid * 0x9E3779B1;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    /**
     * The only poses a mount may hold: idle standing or the walk cycle, on solid
     * ground. Mirrors {@code MovementCommands.isStanding/isWalking}; anything else
     * (jump/fall/swim/rope/chair/skill) is a dismount.
     */
    private static boolean isMountPose(Character bot) {
        if (bot.getChair() > 0) {
            return false; // seated
        }
        if (GCMovement.isEnabled(bot) && !GCMovement.isGrounded(bot)) {
            return false; // mid-air / swimming / on a rope under GC control
        }
        int stance = bot.getStance();
        // 2/3 = MOVING_RIGHT/LEFT, 4/5 = IDLE_RIGHT/LEFT (MovementEnums.StanceValues).
        return stance == 2 || stance == 3 || stance == 4 || stance == 5;
    }

    private static boolean mapForbidsMounts(Character bot) {
        MapleMap map = bot.getMap();
        return map != null && FieldLimit.CANNOTUSEMOUNTS.check(map.getFieldLimit());
    }

    /**
     * Put the bot astride {@code mountItemId}. Sets the {@link Mount} object the
     * look packet reads, registers the riding buff (so
     * {@code getBuffedValue(MONSTER_RIDING)} is non-null), and broadcasts
     * {@code showMonsterRiding} so everyone already on the map sees it — the same
     * three steps the host's StatEffect rider path performs for a real player.
     */
    private static void mount(Character bot, int mountItemId) {
        Skill skill = SkillFactory.getSkill(RIDE_SKILL);
        if (skill == null || skill.getMaxLevel() < 1) {
            return; // rider skill missing from WZ — nothing to ride with
        }
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) {
            return;
        }

        // v83 mountId convention (Character.loadCharFromDB): jobType*10000000 + 1004.
        int mountId = bot.getJobType() * 10000000 + RIDE_SKILL;
        Mount mount = bot.getMapleMount();
        if (mount == null) {
            mount = new Mount(bot, mountItemId, mountId);
            bot.setMapleMount(mount);
        } else {
            mount.setItemId(mountItemId);
            mount.setSkillId(mountId);
        }

        long now = Server.getInstance().getCurrentTime();
        long duration = effect.getDuration() > 0 ? effect.getDuration() : RIDE_SKILL; // 1004: time=2100000ms
        // Non-silent so the buff lands in the character's buff holders; that registration
        // is precisely what every later spawn/warp packet reads to draw the mount.
        bot.registerEffect(effect, now, now + duration, false);
        bot.getMap().broadcastMessage(bot,
                PacketCreator.showMonsterRiding(bot.getId(), mount), false);
    }

    /**
     * Take the bot off its mount. Cancelling the riding buff makes the host drop the
     * buff stat and broadcast the foreign-buff cancel, so observers remove the mount
     * model — same as a player dismounting. No-op if the bot isn't riding.
     */
    private static void dismount(Character bot) {
        if (bot == null || !isRiding(bot)) {
            return;
        }
        bot.cancelEffectFromBuffStat(BuffStat.MONSTER_RIDING);
    }
}
