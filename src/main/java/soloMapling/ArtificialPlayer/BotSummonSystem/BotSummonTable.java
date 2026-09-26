package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.constants.skills.Bishop;
import org.gms.constants.skills.Bowmaster;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.DarkKnight;
import org.gms.constants.skills.FPArchMage;
import org.gms.constants.skills.ILArchMage;
import org.gms.constants.skills.Marksman;
import org.gms.constants.skills.Outlaw;
import org.gms.constants.skills.Priest;
import org.gms.constants.skills.Ranger;
import org.gms.constants.skills.Sniper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * The per-skill behaviour of a bot's summon. A summon is a real host Summon entity. A client only
 * drives the summon it owns (the local player's own); every other summon, a bot's included, is
 * rendered by observers purely from the MOVE_SUMMON frames the server relays - and a bot has no
 * client to produce them, so BotSummonFollower authors the movement itself (see that class). So
 * the per-skill choices here are (a) the spawn movementType byte - which mirrors the host exactly -
 * and (b) whether it attacks. The ACTUAL movement (the pet-style follow behind the owner) is authored
 * by the follower for every non-stationary summon, so the byte no longer selects an orbit vs a
 * hover. Damage is NOT a property
 * here: like every other bot hit it comes from the bot's job tier + level via BotDamageModel.
 *
 * Movement kind mirrors the host's own StatEffect.getSummonMovementType() (the SUMMON statup) so
 * the spawn packet carries the same movementType a real client would. A STATIONARY entry (octopus
 * turret) is sent movementType 0 and is never repositioned: a placed cannon sits where it was
 * placed, which is exactly what that movementType promises. The rest send the host's own byte -
 * 3 (CIRCLE_FOLLOW) for the archer birds / dragon, 1 (FOLLOW) for the mage elementals / bahamut /
 * beholder.
 *
 * Deliberately NOT registered: the archer Puppet (3111002/3211002). Its only function is to pull mob
 * aggro, and the host gates that on the PUPPET buff stat (Monster.isCharacterPuppetInVicinity reads
 * getBuffEffect(BuffStat.PUPPET)). We register no buff, so a bot puppet would be an inert decoration
 * that misleads observers; it is left out rather than shipped broken.
 *
 * No org.gms.client.Job reference: a summon's owning job is the skill id's own job prefix and
 * lineage is derived from job-id hierarchy, so the table is pure data and loads without a Spring
 * context (unit-testable).
 */
public final class BotSummonTable {

    /**
     * How the server holds the summon. This names the spawn packet's {@code nMoveAbility} byte, and
     * is kept IDENTICAL to the host's own {@code StatEffect.getSummonMovementType()} so a bot's
     * summon carries the same byte a real player's would (the client reads it for its initial-action
     * fallback, so a mismatch is a parity break, not decoration).
     *
     * <p>Note this is the WIRE byte, not the movement the plugin authors: the follower moves every
     * non-stationary summon with the same pet-style follow behind the owner (see
     * {@link BotSummonFollower}), so {@code FOLLOW} and {@code CIRCLE_FOLLOW} differ only in the byte
     * they send.</p>
     */
    public enum Move {
        /** Placed where cast and held there (octopus turret). movementType 0, never repositioned. */
        STATIONARY,
        /** movementType 1 - the host's value for the mage elementals / bahamut / beholder. */
        FOLLOW,
        /** movementType 3 - the host's value for the archer birds (hawk / eagle / phoenix / dragon). */
        CIRCLE_FOLLOW
    }

    /**
     * One summon's behaviour.
     *
     * @param skillId     the summon skill (also the Summon's owning key)
     * @param move        how the server holds it (STATIONARY = sits where spawned)
     * @param attacks     whether the server periodically makes it strike a nearby mob
     * @param attackLines damage lines per strike (1 for every v83 summon here)
     */
    public record Spec(int skillId, Move move, boolean attacks, int attackLines) {
        public boolean isStationary() {
            return move == Move.STATIONARY;
        }

        /** The job id this summon belongs to - the skill id's own job prefix. */
        public int jobId() {
            return skillId / 10000;
        }
    }

    private static final Map<Integer, Spec> BY_SKILL = new LinkedHashMap<>();

    static {
        // ---- Archer birds (host byte: CIRCLE_FOLLOW) ----
        add(Ranger.SILVER_HAWK, Move.CIRCLE_FOLLOW, true, 1);   // 3111005
        add(Sniper.GOLDEN_EAGLE, Move.CIRCLE_FOLLOW, true, 1);  // 3211005
        add(Bowmaster.PHOENIX, Move.CIRCLE_FOLLOW, true, 1);    // 3121006
        add(Marksman.FROST_PREY, Move.CIRCLE_FOLLOW, true, 1);  // 3221005

        // ---- Magician (host byte: FOLLOW) ----
        add(FPArchMage.ELQUINES, Move.FOLLOW, true, 1);     // 2121005
        add(ILArchMage.IFRIT, Move.FOLLOW, true, 1);        // 2221005

        // ---- Priest / Bishop ----
        add(Priest.SUMMON_DRAGON, Move.CIRCLE_FOLLOW, true, 1); // 2311006 (host byte 3)
        add(Bishop.BAHAMUT, Move.FOLLOW, true, 1);              // 2321003

        // ---- Dark Knight (follow, support only - no attack) ----
        add(DarkKnight.BEHOLDER, Move.FOLLOW, false, 0);    // 1321007

        // ---- Pirate turrets (STATIONARY, attacking) ----
        add(Outlaw.OCTOPUS, Move.STATIONARY, true, 1);              // 5211001
        add(Corsair.WRATH_OF_THE_OCTOPI, Move.STATIONARY, true, 1); // 5220002
    }

    private BotSummonTable() {}

    private static void add(int skillId, Move move, boolean attacks, int lines) {
        BY_SKILL.put(skillId, new Spec(skillId, move, attacks, lines));
    }

    /** The behaviour for a summon skill, or null when it is not a registered summon. */
    public static Spec forSkill(int skillId) {
        return BY_SKILL.get(skillId);
    }

    /** True when this skill id is a bot summon. */
    public static boolean isSummonSkill(int skillId) {
        return BY_SKILL.containsKey(skillId);
    }

    /**
     * Every summon skill this job id owns across its lineage, in registration order. Ownership is
     * derived from job-id hierarchy (the host's own isA rule), so an advanced job keeps what each
     * earlier tier gained - a Bishop owns both SUMMON_DRAGON and BAHAMUT.
     */
    public static List<Integer> summonsForJobId(int jobId) {
        List<Integer> result = new ArrayList<>();
        for (Spec spec : BY_SKILL.values()) {
            if (isA(jobId, spec.jobId())) {
                result.add(spec.skillId());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * The host's {@code Job.isA} rule, expressed on raw job ids: true when {@code jobId} is
     * {@code baseJobId} or a descendant of it in the same branch.
     */
    static boolean isA(int jobId, int baseJobId) {
        int baseBranch = baseJobId / 10;
        return (jobId / 10 == baseBranch && jobId >= baseJobId)
                || (baseBranch % 10 == 0 && jobId / 100 == baseJobId / 100);
    }

    /** The character level the 2nd job caps out at - the day a 3rd-job summon can be learned. */
    static final int THIRD_JOB_LEVEL = 70;
    /** The character level the 3rd job caps out at - the day a 4th-job summon can be learned. */
    static final int FOURTH_JOB_LEVEL = 120;

    // Skill points a level-up hands out (the host's level_up_sp_gain default) and what one skill
    // level costs, so a granted summon fills in at the pace a player's own would - the same pair
    // BotEnergyCharge.skillLevelForBot uses for its third-job skill.
    private static final int SP_PER_LEVEL = 3;

    /**
     * The skill level a bot of this character level would plausibly hold, at the host's own SP rate:
     * 1 on the day its job tier advances, then {@code SP_PER_LEVEL} skill levels per character
     * level. A 30-level summon therefore tops out at the entry level + 10 - a level-80 Ranger's
     * hawk and a level-130 Bishop's Bahamut - so a bot reads as a player who has been spending
     * points on the summon, not one who was handed it complete. Returns 0 when the bot has not
     * reached the tier that owns the skill.
     *
     * <p>This is the ONE place the granted level is decided. It matters beyond cosmetics: the
     * strike's whole WZ row is read at this level, so a fresh Ranger's hawk stuns at its level-1
     * {@code prop} (50%) instead of its 30th (99%), and a Bishop's Bahamut reaches its 3-mob
     * {@code mobCount} before its 6-mob one. Mirrors {@code BotEnergyCharge.skillLevelForBot}.</p>
     */
    public static int skillLevelForBot(int characterLevel, int skillId, int maxLevel) {
        int entry = isFourthJobSummon(skillId) ? FOURTH_JOB_LEVEL : THIRD_JOB_LEVEL;
        if (characterLevel < entry) {
            return 0;
        }
        return Math.max(1, Math.min(maxLevel, 1 + (characterLevel - entry) * SP_PER_LEVEL));
    }

    /** The host's own fourth-job test ({@code Skill.isFourthJob}) on the skill's job prefix. */
    static boolean isFourthJobSummon(int skillId) {
        return (skillId / 10000) % 10 == 2;
    }
}
