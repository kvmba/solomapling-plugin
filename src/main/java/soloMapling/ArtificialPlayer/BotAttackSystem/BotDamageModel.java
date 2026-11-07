package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.server.StatEffect;

import java.util.concurrent.ThreadLocalRandom;

/*
 * Original. The single place that decides how hard a bot hits. Damage is combat feel, not balance
 * (bots are decoration), so every number here is meant to be eyeballed in-game and tuned freely.
 *
 * <p>The model is calibrated against v83's own damage formula, evaluated for a player of the bot's
 * level with plausible gear (5 AP per level into the main stat, a level-appropriate weapon):
 *
 * <pre>  MAX   = ((MAIN*4 + SECOND) * ATK / 100) * (skill damage% / 100)
 *   swing = (MAX * mastery + MAX) / 2 * lines        mastery 0.10 .. 0.60 by skill level  </pre>
 *
 * <p>{@link #TIER_BAND} is that formula's value for a 100%-damage single-line swing minus the level
 * ramp, i.e. the bot's raw attack power at its advancement. The skill's own WZ percentage is applied
 * on top, so Power Strike (260%) and Thunderbolt (60%) land where a real player's would instead of
 * both receiving one flat band.
 *
 * <p>Two things this replaces, both measured against the formula above:
 * <ul>
 *   <li>The bands used to run 3.3x too hot by the 4th job (tier 2 was 1.40x, tier 4 2.55x), so a
 *       maxed bot out-damaged the players it was meant to look like at every advancement.</li>
 *   <li>A "single/double-line skills hit 2.0-2.5x harder" multiplier made up for the ignored skill
 *       percentages. It keyed off the LINE COUNT rather than the skill, so a 60% Thunderbolt took
 *       the same per-line hit as a 260% Power Strike and Gunslinger's Double Shot came out ~12x a
 *       real player's. The WZ percentage is the real axis; the multiplier is gone.</li>
 * </ul>
 *
 * <p>The tier is capped by the LEVEL ({@link BotSkillLevelGate#tierForLevel}): a bot's job is rolled
 * from its spawn-time band and can outlive a level override (the LPQ lobby pinned level-78 jobs to
 * 38), and a level-38 "Chief Bandit" must hit like a 2nd job, not a 3rd. The cap lives HERE rather
 * than at each strike site so every bot hit - swings, summons, mists, final attacks - agrees.
 */
public final class BotDamageModel {

    private BotDamageModel() {}

    // Raw attack power by job tier (index 0..4 = beginner / 1st / 2nd / 3rd / 4th): the value of a
    // 100%-damage single-line swing, before the level ramp. Each row is the v83 formula's output at
    // that advancement's mid-level (20 / 40 / 85 / 140) with the level ramp divided back out, and the
    // spread is the original band's ratio kept intact. {min, max}, both inclusive.
    private static final int[][] TIER_BAND = {
            {   20,    40 },  // 0 beginner - plain weapon swing
            {   76,   174 },  // 1 first job   (lv 20)
            {  171,   443 },  // 2 second job  (lv 40)
            {  497,  1271 },  // 3 third job   (lv 85)
            { 1098,  2549 },  // 4 fourth job  (lv 140)
    };

    // Gentle within-tier / cross-level ramp: adds level/LEVEL_RAMP_DIVISOR of the rolled base.
    // lv10 ~+4%, lv70 ~+28%, lv120 ~+48%. Tier stays the dominant axis.
    private static final double LEVEL_RAMP_DIVISOR = 250.0;

    /** A skill-less basic swing, and the fallback when Skill.wz carries no percentage to read. */
    public static final int PLAIN_SWING_PERCENT = 100;

    // Skill points a level hands out divided into what one skill level costs: 3. The same rate
    // BotSummonTable.skillLevelForBot and BotEnergyCharge use, so a bot's kit fills in at the pace a
    // player's would rather than arriving maxed.
    private static final int SP_PER_LEVEL = 3;

    /**
     * One per-line damage roll for a bot of this job tier and level, on a swing whose skill carries
     * {@code skillPercent} damage.
     *
     * @param skillPercent the WZ per-line damage percentage (100 = a plain swing); values <= 0 fall
     *                     back to {@link #PLAIN_SWING_PERCENT}
     */
    public static int rollLine(int jobTier, int level, int skillPercent) {
        int tier = Math.max(0, Math.min(TIER_BAND.length - 1,
                Math.min(jobTier, BotSkillLevelGate.tierForLevel(level))));
        int[] band = TIER_BAND[tier];
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int base = (band[0] >= band[1]) ? band[0] : rng.nextInt(band[0], band[1] + 1);

        if (level > 0) {
            base = (int) Math.round(base * (1.0 + level / LEVEL_RAMP_DIVISOR));
        }
        int percent = skillPercent > 0 ? skillPercent : PLAIN_SWING_PERCENT;
        if (percent != PLAIN_SWING_PERCENT) {
            base = (int) Math.round(base * (percent / 100.0));
        }
        return Math.max(1, base);
    }

    /**
     * The WZ per-line damage percentage of {@code skillId} at the level this bot holds it, or
     * {@link #PLAIN_SWING_PERCENT} when the skill is unknown or carries no readable percentage (a
     * skill-less swing, a summon's own strike, Meso Explosion's formula-driven blast).
     *
     * <p>Magic attacks carry {@code mad} where physical ones carry {@code damage}, and StatEffect
     * defaults the latter to 100 - so a row is treated as magic when it has a matk and its damage
     * field is still at that default.
     */
    public static int skillPercent(int skillId, int botLevel) {
        if (skillId <= 0) {
            return PLAIN_SWING_PERCENT;
        }
        StatEffect effect = effectFor(skillId, botLevel);
        if (effect == null) {
            return PLAIN_SWING_PERCENT;
        }
        int damage = effect.getDamage();
        int matk = effect.getMatk();
        if (matk > 0 && damage == PLAIN_SWING_PERCENT) {
            return matk;
        }
        return damage > 0 ? damage : PLAIN_SWING_PERCENT;
    }

    /**
     * How many damage lines {@code skillId} actually draws at the level this bot holds it, capped by
     * what the caller's profile authored.
     *
     * <p>Only a count the WZ <em>states</em> may shrink the authored swing. Several skills ship no
     * attackCount/bulletCount at all - Back Spin Blow (5101002) and Battleship Torpedo (5221008)
     * among them - and StatEffect reports the default 1 for those. Treating that default as
     * authoritative would silently cut those attacks down to a single line.
     */
    public static int linesFor(int skillId, int botLevel, int profileLines) {
        if (skillId <= 0 || profileLines <= 1) {
            return profileLines;
        }
        StatEffect effect = effectFor(skillId, botLevel);
        if (effect == null) {
            return profileLines;
        }
        return capLines(effect.getAttackCount(), effect.getBulletCount(), profileLines);
    }

    /**
     * The pure half of {@link #linesFor}: which of the two WZ line fields applies, and the cap.
     *
     * <p>Ranged and thrown skills split their hits with {@code bulletCount}; everything else uses
     * {@code attackCount}. A stated count of 1 or less is indistinguishable from an absent field, so
     * it defers to the authored count instead of overriding it.
     */
    static int capLines(int attackCount, int bulletCount, int profileLines) {
        int stated = Math.max(attackCount, bulletCount);
        if (stated <= 1) {
            return profileLines;
        }
        return Math.min(profileLines, stated);
    }

    /**
     * The level a bot of {@code botLevel} is assumed to hold {@code skillId} at: 1 the day its
     * advancement opens, then {@link #SP_PER_LEVEL} per level, capped at the skill's own max.
     *
     * <p>Ambient bots never register skills server-side, so a {@code Character.getSkillLevel} lookup
     * would answer 0 for every one of them and lock the whole job out of its own kit.
     */
    public static int skillLevelFor(int botLevel, int skillId) {
        int floor = Math.max(1, BotSkillLevelGate.minLevelForSkill(skillId));
        return Math.max(1, 1 + Math.max(0, botLevel - floor) * SP_PER_LEVEL);
    }

    /**
     * Whether a WZ chance passive fires right now, at the level this bot holds it. The host's own
     * {@code StatEffect.makeChanceResult} reads the skill's prop, so nothing is re-derived here.
     */
    public static boolean rollsChance(int skillId, int botLevel) {
        StatEffect effect = effectFor(skillId, botLevel);
        return effect != null && effect.makeChanceResult();
    }

    /** The skill's WZ effect at this bot's derived skill level, or null when unreadable. */
    private static StatEffect effectFor(int skillId, int botLevel) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return null;
        }
        int maxLevel = skill.getMaxLevel();
        if (maxLevel <= 0) {
            return null;
        }
        int level = Math.min(maxLevel, skillLevelFor(botLevel, skillId));
        try {
            return skill.getEffect(level);
        } catch (IndexOutOfBoundsException ignored) {
            return null; // a WZ row shorter than maxLevel - treat as unreadable, never crash a swing
        }
    }
}
