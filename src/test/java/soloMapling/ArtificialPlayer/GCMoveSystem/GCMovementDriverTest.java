package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GCMovementDriverTest {
    @Test
    void compensatesForNormalTickExecutionTime() {
        assertEquals(1_050L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 50L));
    }

    @Test
    void skipsMissedDeadlinesAfterAnOverrun() {
        assertEquals(1_150L, GCMovementDriver.nextDeadline(1_000L, 1_120L, 50L));
    }

    @Test
    void preservesLodCadencesAndSupportsTierChanges() {
        assertEquals(1_250L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 250L));
        assertEquals(2_000L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 1_000L));
        assertEquals(1_050L, GCMovementDriver.nextDeadline(1_000L, 1_018L, 50L));
    }
}
