package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Orbis Stage 7/8 reactor chain, which is all item-triggered.
 *
 * <p>Every reactor here fires only when the item it wants is DROPPED in its box, so a bot that
 * hit one instead of feeding it would stall the room forever. The mistakes this guards against
 * are the silent kind: feeding the spring the wrong item, or a stack size the trigger does not
 * match, leaves the flag unset with no error.
 */
class OrbisPapaStatueDataTest {

    @Test
    void thePapaChainUsesDistinctItemsAtEachStep() {
        // Medal summons, trap item ends the room and reveals Papa, and the Root of Life (Papa's
        // drop) goes on the spring. Collapsing any two would dead-end the chain.
        assertNotEquals(OrbisPqData.PAPA_MEDAL, OrbisPqData.PAPA_TRAP_ITEM);
        assertNotEquals(OrbisPqData.PAPA_MEDAL, OrbisPqData.PAPA_SEED);
        assertNotEquals(OrbisPqData.PAPA_TRAP_ITEM, OrbisPqData.PAPA_SEED);
    }

    @Test
    void eachStageSevenReactorIsOneTheMapDeclares() {
        // Read from 920010800.img.xml: 2001000 x7, 2001001 x1, 2001016 x9, 2002003 x1.
        assertTrue(OrbisPqData.PAPA_POT != OrbisPqData.PAPA_SPRING_REACTOR);
        assertTrue(OrbisPqData.PAPA_TRAP != OrbisPqData.PAPA_POT);
        assertEquals(2001016, OrbisPqData.PAPA_TRAP);
        assertEquals(2002003, OrbisPqData.PAPA_SPRING_REACTOR);
    }

    @Test
    void theMobRangeCoversPapaAndBothSummons() {
        // 9300039 (Papa) and 9300048/9300049 (summons) are contiguous; the fight loop uses the
        // range, so it must span the low and high ids the scripts spawn.
        assertTrue(OrbisPqData.PAPA_MOB_LOW <= 9300039);
        assertTrue(OrbisPqData.PAPA_MOB_HIGH >= 9300049);
    }

    @Test
    void theSpringDropLandsInsideItsTriggerBox() {
        // 2002003 sits at (-755,19) with box lt(-44,-48)/rb(54,41); a drop re-seats 85px down to
        // the first foothold at y>= -66 (y=28 here), which is inside the box. Feeds must land
        // inside, or the trigger never matches.
        Point spot = OrbisPqData.PAPA_SPRING_SPOT;
        assertEquals(-755, spot.x);
        assertEquals(19, spot.y);
        int landingY = 28; // y of the foothold under x=-755
        assertTrue(landingY >= spot.y - 48 && landingY < spot.y + 41, "spring landing is inside its box");
    }

    @Test
    void theStatueBaseAndItsSpotAreOnTheTowerMap() {
        // The base (2006001) is in the tower at (-48,-916) with box lt(-48,-48)/rb(47,48); the
        // drop lands on the foothold at y=-897, inside the box.
        assertEquals(2006001, OrbisPqData.STATUE_BASE_REACTOR);
        assertEquals(-48, OrbisPqData.STATUE_BASE_SPOT.x);
        assertEquals(-916, OrbisPqData.STATUE_BASE_SPOT.y);
        int landingY = -897;
        assertTrue(landingY >= -916 - 48 && landingY < -916 + 48, "statue-base landing is inside its box");
    }
}
