package soloMapling.ArtificialPlayer.BotHealthSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotHealthFloorTest {

    @Test
    void floorIsFivePercentOfThePool() {
        assertEquals(50, BotHealthFloor.floorFor(1000));
        assertEquals(79, BotHealthFloor.floorFor(1568)); // ceil, not floor — keeps the sliver visible
        assertEquals(1500, BotHealthFloor.floorFor(30000));
    }

    @Test
    void tinyPoolsStillLeaveOneHp() {
        // A level-1 bot has ~50 HP, so 5% is 2 or 3 — small enough to look empty. Never go
        // below 1, though: reaching 0 is the death flow's job, not the damage clamp's.
        assertEquals(1, BotHealthFloor.floorFor(0));
        assertEquals(1, BotHealthFloor.floorFor(1));
        assertEquals(1, BotHealthFloor.floorFor(10));
    }

    @Test
    void unknownPoolNeverAmplifiesAHit() {
        // A stat race handing us 0 must not turn every hit into a kill.
        assertEquals(1, BotHealthFloor.floorFor(-5));
    }

    @Test
    void atFloorRecognisesABotOnItsLastLegs() {
        assertTrue(BotHealthFloor.atFloor(50, 1000));
        assertTrue(BotHealthFloor.atFloor(10, 1000)); // below the floor counts too
        assertFalse(BotHealthFloor.atFloor(51, 1000));
        assertFalse(BotHealthFloor.atFloor(1000, 1000));
    }

    @Test
    void floorScalesAcrossTheWholeLevelRange() {
        // Guard against someone "simplifying" this back to a flat 1 HP: at level 200 the pool
        // is 4438, so the floor has to grow with it or the bar is empty again.
        assertTrue(BotHealthFloor.floorFor(50) >= 1);
        assertTrue(BotHealthFloor.floorFor(4438) > 100);
        assertTrue(BotHealthFloor.floorFor(30000) > 1000);
    }
}
