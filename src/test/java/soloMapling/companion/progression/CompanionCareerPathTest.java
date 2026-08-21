package soloMapling.companion.progression;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionCareerPathTest {

    @Test
    void personaSeedSelectsStableExplorerFirstJob() {
        int selected = CompanionCareerPath.nextJobId(0, 10, 12345L).orElseThrow();

        assertTrue(Set.of(100, 200, 300, 400, 500).contains(selected));
        assertEquals(selected, CompanionCareerPath.nextJobId(0, 10, 12345L).orElseThrow());
        assertFalse(CompanionCareerPath.nextJobId(0, 9, 12345L).isPresent());
    }

    @Test
    void persistedBuildFixesTheCompleteLineageBeforeFirstJob() {
        CompanionCareerBuild build = CompanionCareerBuild.MARKSMAN;

        assertEquals(300, CompanionCareerPath.nextJobId(0, 10, build).orElseThrow());
        assertEquals(320, CompanionCareerPath.nextJobId(300, 30, build).orElseThrow());
        assertEquals(321, CompanionCareerPath.nextJobId(320, 70, build).orElseThrow());
        assertEquals(322, CompanionCareerPath.nextJobId(321, 120, build).orElseThrow());
        assertFalse(CompanionCareerPath.nextJobId(310, 70, build).isPresent());
    }

    @Test
    void magicianFirstJobUsesClassicLevelEightThreshold() {
        assertEquals(200, CompanionCareerPath.nextJobId(
                0, 8, CompanionCareerBuild.BISHOP).orElseThrow());
        assertFalse(CompanionCareerPath.nextJobId(
                0, 7, CompanionCareerBuild.BISHOP).isPresent());
    }

    @Test
    void manualFirstJobIsRespectedAndBranchRemainsStable() {
        int warriorBranch = CompanionCareerPath.nextJobId(100, 30, 77L).orElseThrow();

        assertTrue(Set.of(110, 120, 130).contains(warriorBranch));
        assertEquals(warriorBranch + 1,
                CompanionCareerPath.nextJobId(warriorBranch, 70, 999L).orElseThrow());
        assertEquals(warriorBranch + 2,
                CompanionCareerPath.nextJobId(warriorBranch + 1, 120, 1L).orElseThrow());
    }

    @Test
    void eachExplorerFamilyUsesOnlyItsOwnBranches() {
        assertTrue(Set.of(210, 220, 230).contains(
                CompanionCareerPath.nextJobId(200, 30, 10L).orElseThrow()));
        assertTrue(Set.of(310, 320).contains(
                CompanionCareerPath.nextJobId(300, 30, 10L).orElseThrow()));
        assertTrue(Set.of(410, 420).contains(
                CompanionCareerPath.nextJobId(400, 30, 10L).orElseThrow()));
        assertTrue(Set.of(510, 520).contains(
                CompanionCareerPath.nextJobId(500, 30, 10L).orElseThrow()));
    }

    @Test
    void unsupportedJobsAndPrematureThresholdsDoNotAdvance() {
        assertFalse(CompanionCareerPath.nextJobId(100, 29, 1L).isPresent());
        assertFalse(CompanionCareerPath.nextJobId(110, 69, 1L).isPresent());
        assertFalse(CompanionCareerPath.nextJobId(111, 119, 1L).isPresent());
        assertFalse(CompanionCareerPath.nextJobId(900, 200, 1L).isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> CompanionCareerPath.nextJobId(0, 0, 1L));
    }
}
