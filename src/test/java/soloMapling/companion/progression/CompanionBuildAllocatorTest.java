package soloMapling.companion.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionBuildAllocatorTest {

    @Test
    void explorerFamiliesUseTheirCombatPrimaryStat() {
        assertEquals(CompanionBuildAllocator.PrimaryStat.STR,
                CompanionBuildAllocator.primaryStatForJobId(112));
        assertEquals(CompanionBuildAllocator.PrimaryStat.INT,
                CompanionBuildAllocator.primaryStatForJobId(232));
        assertEquals(CompanionBuildAllocator.PrimaryStat.DEX,
                CompanionBuildAllocator.primaryStatForJobId(322));
        assertEquals(CompanionBuildAllocator.PrimaryStat.LUK,
                CompanionBuildAllocator.primaryStatForJobId(412));
        assertEquals(CompanionBuildAllocator.PrimaryStat.STR,
                CompanionBuildAllocator.primaryStatForJobId(522));
    }

    @Test
    void beginnerAndUnsupportedJobsDoNotConsumeAp() {
        assertEquals(CompanionBuildAllocator.PrimaryStat.NONE,
                CompanionBuildAllocator.primaryStatForJobId(0));
        assertEquals(CompanionBuildAllocator.PrimaryStat.NONE,
                CompanionBuildAllocator.primaryStatForJobId(900));
    }
}
