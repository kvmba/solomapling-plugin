package soloMapling.ArtificialPlayer.BotTypes.Zakum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks Zakum PQ's crate list and ore identity against the host's own data.
 *
 * <p>Ordinary crates can be struck; the only chest that drops the ore consumes seven keys.
 */
class ZakumPqDataTest {

    @Test
    void theOreChestIsNotAnOrdinaryCrate() {
        assertTrue(ZakumPqData.isQuestRoom(280011005));
        assertEquals(2112014, ZakumPqData.ORE_CHEST);
        for (int crate : ZakumPqData.CRATES) {
            assertNotEquals(ZakumPqData.ORE_CHEST, crate);
        }
    }

    @Test
    void theTurnInWantsTheOreNotTheRefinedPiece() {
        // 2032002.js grades cm.haveItem(4001018) on the leader; 4031061 is the piece Aura
        // gives to each member AFTER the clear (and 4031062 its quest-item twin), so the
        // constant the bot loots for must be the raw ore.
        assertEquals(4001018, ZakumPqData.FIRE_ORE);
        assertEquals(4031061, ZakumPqData.FIRE_ORE_REFINED);
    }
}
