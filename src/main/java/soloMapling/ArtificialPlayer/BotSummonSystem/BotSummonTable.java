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
 * The per-skill behaviour of a bot's summon. A summon is a real map entity (host Summon) with its
 * own lifecycle - spawn, move/follow, attack, remove - so each one needs more than an id: how it
 * moves and whether (and how hard) it hits. This is the single place those choices live, keyed by
 * the summon skill ids each class owns.
 *
 * Movement type mirrors the host's own StatEffect.getSummonMovementType() (the SUMMON / PUPPET
 * statup), so a bot behaves like a real client. STATIONARY summons (the pirate turrets and the
 * archer puppet) MUST NOT be moved by the follower: a real octopus is a placed cannon, not a pet.
 * Damage is NOT a property here - it comes from the bot's job tier + level via BotDamageModel,
 * exactly like every other bot hit.
 *
 * No org.gms.client.Job reference: a summon's owning job is the skill id's own job prefix
 * (skillId / 10000), and lineage is derived from job-id hierarchy - so the table is pure data and
 * loads without a Spring context (unit-testable).
 */
public final class BotSummonTable {

    /** How a summon is positioned by the follower. STATIONARY is never moved once spawned. */
    public enum Move {
        /** Placed where cast (octopus, puppet). Never moved. */
        STATIONARY,
        /** Hovers near a fixed offset from the owner (mage / beholder / dragon summons). */
        FOLLOW,
        /** Orbits the owner on a slowly turning ring (archer hawks / eagles). */
        CIRCLE
    }

    /**
     * One summon's behaviour.
     *
     * @param skillId     the summon skill (also the Summon's owning key)
     * @param move        how the follower positions it (STATIONARY = never move)
     * @param attacks     whether it periodically strikes a nearby mob
     * @param attackLines damage lines per strike (1 for every v83 summon here)
     */
    public record Spec(int skillId, Move move, boolean attacks, int attackLines) {
        /** A moving summon floats (hawk / phoenix / elquines / beholder / dragon); a stationary one sits on the ground. */
        public boolean airborne() {
            return move != Move.STATIONARY;
        }

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
        // ---- Archer (CIRCLE_FOLLOW, attacking) ----
        add(Ranger.SILVER_HAWK, Move.CIRCLE, true, 1);      // 3111005
        add(Sniper.GOLDEN_EAGLE, Move.CIRCLE, true, 1);     // 3211005
        add(Bowmaster.PHOENIX, Move.CIRCLE, true, 1);       // 3121006
        add(Marksman.FROST_PREY, Move.CIRCLE, true, 1);     // 3221005
        // ---- Archer decoy (STATIONARY, no attack - it exists to draw mob aggro) ----
        add(Ranger.PUPPET, Move.STATIONARY, false, 0);      // 3111002
        add(Sniper.PUPPET, Move.STATIONARY, false, 0);      // 3211002

        // ---- Magician (FOLLOW / CIRCLE, attacking) ----
        add(FPArchMage.ELQUINES, Move.FOLLOW, true, 1);     // 2121005
        add(ILArchMage.IFRIT, Move.FOLLOW, true, 1);        // 2221005

        // ---- Priest / Bishop (CIRCLE_FOLLOW / FOLLOW, attacking) ----
        add(Priest.SUMMON_DRAGON, Move.CIRCLE, true, 1);    // 2311006
        add(Bishop.BAHAMUT, Move.FOLLOW, true, 1);          // 2321003

        // ---- Dark Knight (FOLLOW, support only - no attack) ----
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
}
