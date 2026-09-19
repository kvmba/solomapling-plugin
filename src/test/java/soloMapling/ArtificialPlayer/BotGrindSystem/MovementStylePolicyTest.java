package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the job -> grind-movement-style table, especially the pirate lines added alongside the class.
 */
class MovementStylePolicyTest {

    @Test
    void gunslingerLineHoldsDistance() {
        // A gun is ranged: Gunslinger / Outlaw / Corsair keep their distance like a bowman.
        assertEquals(MovementStyle.RANGED, MovementStylePolicy.forJobId(520));
        assertEquals(MovementStyle.RANGED, MovementStylePolicy.forJobId(521));
        assertEquals(MovementStyle.RANGED, MovementStylePolicy.forJobId(522));
    }

    @Test
    void brawlerLineStaysPlanted() {
        // A knuckle is melee: Brawler / Marauder / Buccaneer walk and plant, never kite.
        assertEquals(MovementStyle.PLANTED, MovementStylePolicy.forJobId(510));
        assertEquals(MovementStyle.PLANTED, MovementStylePolicy.forJobId(511));
        assertEquals(MovementStyle.PLANTED, MovementStylePolicy.forJobId(512));
        // 1st-job pirate (weapon-uncommitted) also stays planted, like 1st-job bowman.
        assertEquals(MovementStyle.PLANTED, MovementStylePolicy.forJobId(500));
    }

    @Test
    void otherClassesAreUnaffected() {
        assertEquals(MovementStyle.PLANTED, MovementStylePolicy.forJobId(100)); // 1st warrior
        assertEquals(MovementStyle.TELEPORT, MovementStylePolicy.forJobId(211)); // F/P mage
        assertEquals(MovementStyle.FLASH_JUMP, MovementStylePolicy.forJobId(412)); // Night Lord
        assertEquals(MovementStyle.JUMP_ATTACK, MovementStylePolicy.forJobId(421)); // Chief Bandit
        assertEquals(MovementStyle.RANGED, MovementStylePolicy.forJobId(312)); // Bowmaster
        assertEquals(MovementStyle.PLANTED, MovementStylePolicy.forJobId(0)); // beginner
    }
}
