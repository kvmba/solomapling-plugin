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
}
