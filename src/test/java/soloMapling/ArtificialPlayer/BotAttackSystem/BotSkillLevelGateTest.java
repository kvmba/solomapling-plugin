package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the advancement floors used to gate a bot's skills by its own level.
 *
 * <p>Plain int job ids only: {@code org.gms.client.Job}'s static init needs the host's Spring
 * context, so this stays a pure table test.</p>
 */
class BotSkillLevelGateTest {

    @Test
    void firstJobFloorsExceptTheMagicianLine() {
        assertEquals(10, BotSkillLevelGate.minLevelForJob(100), "warrior");
        assertEquals(10, BotSkillLevelGate.minLevelForJob(300), "bowman");
        assertEquals(10, BotSkillLevelGate.minLevelForJob(400), "thief");
        assertEquals(10, BotSkillLevelGate.minLevelForJob(500), "pirate");
        assertEquals(8, BotSkillLevelGate.minLevelForJob(200), "the magician line advances at 8");
    }

    @Test
    void everyBranchFollowsTheSameTierFloors() {
        // Thief (4xx) and pirate (5xx) share the x0/x1/x2 suffix scheme, so 2nd/3rd/4th job are
        // 30/70/120 on every explorer line.
        for (int[] pair : new int[][]{
                {410, 30}, {420, 30}, {510, 30}, {520, 30}, {110, 30}, {210, 30}, {310, 30},
                {411, 70}, {421, 70}, {511, 70}, {521, 70}, {111, 70}, {211, 70}, {311, 70},
                {412, 120}, {422, 120}, {512, 120}, {522, 120}, {112, 120}, {212, 120}, {312, 120}}) {
            assertEquals(pair[1], BotSkillLevelGate.minLevelForJob(pair[0]), "job " + pair[0]);
        }
    }

    @Test
    void theChiefBanditFromTheReportIsGatedUntil70() {
        assertTrue(38 < BotSkillLevelGate.minLevelForJob(421), "level 38 cannot hold a Chief Bandit");
        assertTrue(69 < BotSkillLevelGate.minLevelForJob(421));
        assertEquals(70, BotSkillLevelGate.minLevelForJob(421));
    }

    @Test
    void aSkillIsGatedByItsOwnJobsFloor() {
        assertEquals(70, BotSkillLevelGate.minLevelForSkill(4211003), "敛财术 is a 3rd-job skill");
        assertEquals(70, BotSkillLevelGate.minLevelForSkill(4111001), "影子金币 / 影网 are Hermit skills");
        assertEquals(120, BotSkillLevelGate.minLevelForSkill(1121008), "Brandish is a 4th-job skill");
        assertEquals(0, BotSkillLevelGate.minLevelForSkill(0), "the skill-less basic swing has no gate");
    }

    @Test
    void aBeginnerHasNoAdvancementGate() {
        assertEquals(0, BotSkillLevelGate.minLevelForJob(0));
    }

    /** The inverse used to cap the damage band: a level cannot hold a tier above its own. */
    @Test
    void theTierAFloorsInverseIsTheHighestTierALevelCanHaveHeld() {
        assertEquals(0, BotSkillLevelGate.tierForLevel(1));
        assertEquals(0, BotSkillLevelGate.tierForLevel(7));
        // The magician line advances at 8, and a level-8 magician really is a 1st job.
        assertEquals(1, BotSkillLevelGate.tierForLevel(8));
        assertEquals(1, BotSkillLevelGate.tierForLevel(9));
        assertEquals(1, BotSkillLevelGate.tierForLevel(10));
        assertEquals(1, BotSkillLevelGate.tierForLevel(29));
        assertEquals(2, BotSkillLevelGate.tierForLevel(30));
        assertEquals(2, BotSkillLevelGate.tierForLevel(69));
        assertEquals(3, BotSkillLevelGate.tierForLevel(70));
        assertEquals(3, BotSkillLevelGate.tierForLevel(119));
        assertEquals(4, BotSkillLevelGate.tierForLevel(120));
        assertEquals(4, BotSkillLevelGate.tierForLevel(200));
    }

    /**
     * {@code tierForLevel} must invert {@code minLevelForJob} for EVERY job: at the lowest level a
     * job can be held, the cap has to still allow that job's own tier. The magician line is the
     * one exception that caught the first cut of this (floor 8, tier 1).
     */
    @Test
    void everyJobsFloorStillAdmitsItsOwnTier() {
        int[][] jobs = {{100, 1}, {110, 2}, {111, 3}, {112, 4},
                {200, 1}, {210, 2}, {211, 3}, {212, 4},
                {300, 1}, {310, 2}, {311, 3}, {312, 4},
                {400, 1}, {410, 2}, {411, 3}, {412, 4},
                {500, 1}, {510, 2}, {511, 3}, {512, 4}};
        for (int[] pair : jobs) {
            int floor = BotSkillLevelGate.minLevelForJob(pair[0]);
            assertTrue(BotSkillLevelGate.tierForLevel(floor) >= pair[1],
                    "job " + pair[0] + " (floor " + floor + ") must admit tier " + pair[1]
                            + ", but the cap allows " + BotSkillLevelGate.tierForLevel(floor));
        }
    }

    /**
     * The report that started this: the LPQ lobby pins a spawn-band job onto a 35-50 level, so a
     * level-38 "Chief Bandit" exists on paper. Its damage must come from the 2nd-job band, not the
     * 3rd-job one - 900..2300/line was what made Alishar evaporate.
     */
    @Test
    void aPinnedThirdJobAtLobbyLevelHitsInTheSecondJobBand() {
        assertEquals(2, BotSkillLevelGate.tierForLevel(38), "level 38 is a 2nd-job level");
        // Raw tier 3 (the job the bot actually holds) must still be capped to the 2nd-job band:
        // 171..443 with the level ramp (1 + 38/250) is 197..510 per line.
        for (int i = 0; i < 200; i++) {
            int line = BotDamageModel.rollLine(3, 38, BotDamageModel.PLAIN_SWING_PERCENT);
            assertTrue(line >= 196 && line <= 511,
                    "a level-38 3rd-job swing must roll in the 2nd-job band, got " + line);
        }
    }

    /** A genuine 4th-job bot (level 130) keeps its own band - the cap must not flatten everyone. */
    @Test
    void aRealFourthJobberKeepsItsOwnBand() {
        // 1098..2549 with the level ramp (1 + 130/250) is 1669..3874.
        for (int i = 0; i < 200; i++) {
            int line = BotDamageModel.rollLine(4, 130, BotDamageModel.PLAIN_SWING_PERCENT);
            assertTrue(line >= 1668 && line <= 3875,
                    "level-130 4th-job swing stays in the 4th-job band, got " + line);
        }
    }
}
