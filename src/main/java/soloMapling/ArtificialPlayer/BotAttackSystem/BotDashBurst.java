package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.server.StatEffect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 疾驰's real movement burst for pirate bots ({@link BotAuraState} keeps the display; this class
 * owns the roll and the lifetime).
 *
 * <p><b>Why a roll.</b> A real pirate double-taps into 疾驰 when they commit to a longer run — not
 * on every step, and not every player uses it the same way. A bot therefore rolls once per
 * qualifying walk: only an uninterrupted one-direction walk of at least {@link #ROLL_MIN_WALK_PX}
 * may roll, exactly one attempt is spent on it, and the roll succeeds with probability
 * {@link #ROLL_CHANCE} — so most short hops stay ordinary, a committed run bursts within seconds,
 * and no two pirates look scripted-identical. The burst then lasts the skill's own WZ duration
 * (20s at max level; GMS083 Skill.wz 5001005: x=30 speed, y=10 jump) and — exactly like the real
 * timed buff — rides through stands, jumps and ropes until it expires. A post-burst cooldown
 * keeps a marathon walker from living in the dash.</p>
 *
 * <p><b>The pose gate.</b> 变身 (TRANSFORMATION / SUPER_TRANSFORMATION), the gunner's 海盗船
 * (BATTLE_SHIP — the host registers it as a MONSTER_RIDING buff) and the 骑宠 mount all own the
 * body/ride, and the real client refuses the dash key in all three. While any of them holds, the
 * roll is refused and the accumulating walk dies; a burst granted BEFORE the pose keeps riding
 * its timer, exactly like a real player's buff would.</p>
 *
 * <p><b>What the burst does to the physics.</b> {@link
 * soloMapling.ArtificialPlayer.GCMoveSystem.BotPhysicsEngine#applyGroundMotion} folds
 * {@link #speedBonus} into the step profile (the same channel a SLOW debuff uses, in the opposite
 * direction), so the walk accelerates to a higher cap and the movement broadcasts carry the
 * higher velocity. The jump impulse and the navigation-graph key stay on the un-burst profile:
 * jump arcs are graph-validated against that profile, so a burst jump that flew farther would
 * overshoot its validated landing — the walk speed alone is the visible dash.</p>
 *
 * <p>Threading: all state is concurrent — the tick runs on the movement thread while a GM cast
 * can start a burst from the macro thread via {@link BotAuraState#onAuraShown}.</p>
 */
public final class BotDashBurst {

    /** Only an uninterrupted one-direction walk this long (px, ~2s at walk speed) may roll. */
    public static final int ROLL_MIN_WALK_PX = 250;
    /** Chance that a qualifying long walk actually bursts — deliberately not every walk. */
    static final double ROLL_CHANCE = 1.0 / 3.0;
    /** Cooldown after a burst ends before the same bot may roll again. */
    static final long ROLL_COOLDOWN_MS = 8_000L;

    /** botId -> burst expiry (epoch ms). Present = live; the physics bonus and the aura pose apply. */
    private static final Map<Integer, Long> BURST_UNTIL = new ConcurrentHashMap<>();
    /** botId -> walk accumulator (origin x + measured direction, 0 until the first displacement). */
    private static final Map<Integer, Walk> WALK = new ConcurrentHashMap<>();
    /** botId -> epoch ms at which the bot may roll again (set at burst start + cooldown). */
    private static final Map<Integer, Long> NEXT_ROLL_AT = new ConcurrentHashMap<>();
    /** botId -> the burst's WZ speed bonus (x, +30 at max level), resolved once per burst. */
    private static final Map<Integer, Integer> SPEED_BONUS = new ConcurrentHashMap<>();

    private record Walk(int originX, int dir) {}

    private BotDashBurst() {}

    /**
     * Tick the burst lifecycle from the movement thread, after the physics tick has settled the
     * bot's motion: expire a due burst, accumulate the walk, and roll exactly once on a
     * qualifying one. A bot whose kit carries no 疾驰 costs one cached map read.
     */
    public static void tickMovement(Character bot, int x, long now) {
        if (bot == null || BotAuraState.dashSkillFor(bot) == 0) {
            return;
        }
        int id = bot.getId();
        Long until = BURST_UNTIL.get(id);
        if (until != null && now >= until) {
            expire(id, now);
        }
        if (BURST_UNTIL.containsKey(id)) {
            return; // already bursting — the buff rides until expiry, no re-rolls mid-burst
        }
        // The pose gate: 变身 / 海盗船 own the body, a 骑宠 mount owns the ride slot — a real
        // client refuses the dash key in all three. Existing bursts keep riding (the timed buff
        // was granted before the pose); only NEW rolls are refused.
        if (poseRefusesDash(BotAuraState.isMorphed(bot),
                bot.getBuffedValue(BuffStat.MONSTER_RIDING) != null)) {
            WALK.remove(id); // a pose change kills the accumulating walk
            return;
        }
        int lengthPx = advanceWalk(id, x);
        if (lengthPx >= 0 && walkQualifies(lengthPx, now, NEXT_ROLL_AT.getOrDefault(id, 0L))) {
            WALK.remove(id); // one attempt per qualifying walk — win or lose
            if (ThreadLocalRandom.current().nextDouble() < ROLL_CHANCE) {
                startBurst(bot, BotAuraState.dashSkillFor(bot));
            }
        }
    }

    /**
     * Pure seam: does the bot's current pose refuse a NEW dash roll? A 变身/海盗船 enabler aura
     * (morphed) and a 骑宠 mount (the ride slot is taken) both do — the same poses the aura
     * display already excludes. The caller passes both live predicates.
     */
    static boolean poseRefusesDash(boolean morphed, boolean mounted) {
        return morphed || mounted;
    }

    /**
     * Advance the walk accumulator for a grounded bot. Returns the walk's one-direction length in
     * px; 0 while the direction is not yet measured; -1 when the bot reversed (the walk restarts
     * from this x). Pure bookkeeping on the id-keyed map, so tests can drive it without a live
     * character.
     */
    static int advanceWalk(int id, int x) {
        Walk walk = WALK.get(id);
        if (walk == null) {
            WALK.put(id, new Walk(x, 0));
            return 0;
        }
        int delta = x - walk.originX();
        if (delta == 0) {
            return 0;
        }
        int dir = Integer.signum(delta);
        if (walk.dir() == 0) {
            WALK.put(id, new Walk(walk.originX(), dir));
            return Math.abs(delta);
        }
        if (dir != walk.dir()) {
            // The bot reversed: the reversal x becomes a fresh origin with the direction
            // unmeasured, so the next displacement re-measures it (a walk must be one-direction).
            WALK.put(id, new Walk(x, 0));
            return -1;
        }
        return Math.abs(delta);
    }

    /**
     * Pure seam: does a walk of {@code lengthPx} qualify for the roll? Long enough, and the bot
     * is off its post-burst cooldown. The caller still rolls {@link #ROLL_CHANCE} on top.
     */
    static boolean walkQualifies(int lengthPx, long now, long nextRollAt) {
        return lengthPx >= ROLL_MIN_WALK_PX && now >= nextRollAt;
    }

    /**
     * Start a burst now: the WZ speed bonus at the skill's max level for the skill's own
     * duration (min 1s). Returns false when the skill cannot be resolved (no burst). A GM cast
     * ({@link BotAuraState#onAuraShown}) and a successful roll both land here.
     */
    static boolean startBurst(Character bot, int skillId) {
        if (bot == null || skillId == 0) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return false;
        }
        StatEffect effect = skill.getEffect(skill.getMaxLevel());
        if (effect == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long duration = Math.max(1_000L, effect.getDuration());
        int id = bot.getId();
        SPEED_BONUS.put(id, effect.getX());
        BURST_UNTIL.put(id, now + duration);
        NEXT_ROLL_AT.put(id, now + duration + ROLL_COOLDOWN_MS);
        return true;
    }

    private static void expire(int id, long now) {
        BURST_UNTIL.remove(id);
        SPEED_BONUS.remove(id);
        WALK.remove(id); // the walk the burst rode is spent; the next one accumulates afresh
        NEXT_ROLL_AT.put(id, now + ROLL_COOLDOWN_MS);
    }

    /** True while the burst is live: the step-profile bonus and the aura pose both key on this. */
    public static boolean isActive(Character bot) {
        return bot != null && BURST_UNTIL.containsKey(bot.getId());
    }

    /** The live burst's WZ speed bonus (0 when inactive). */
    public static int speedBonus(Character bot) {
        return bot == null ? 0 : SPEED_BONUS.getOrDefault(bot.getId(), 0);
    }

    /** Release a despawned bot's burst bookkeeping, alongside {@link BotAuraState#clearBot}. */
    public static void clearBot(int botId) {
        BURST_UNTIL.remove(botId);
        WALK.remove(botId);
        NEXT_ROLL_AT.remove(botId);
        SPEED_BONUS.remove(botId);
    }
}
