package soloMapling.ArtificialPlayer.BotTypes.Zakum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks Zakum PQ's crate list and ore identity against the host's own data.
 *
 * <p>These two facts decide whether the run can finish at all. The Fire Ore the turn-in
 * wants (4001018, Aura's branch in 2032002.js) drops from exactly one reactor - 2112014,
 * per the reactordrops table - which stands in the mine's last room; a crate list without
 * it means the ore never falls and the exit portal stays shut forever. And the refined
 * pieces (4031061/4031062) are what Aura hands OUT afterwards, never what drops, so an
 * ore constant pointing at either of those sends the bot chasing an unobtainable item.
 */
class ZakumPqDataTest {

    @Test
    void theOreDroppingCrateIsInTheList() {
        // 2112014 is the only reactor the reactordrops table feeds the Fire Ore (4001018);
        // it spawns in 280011005, so the bot has to recognise it wherever the party stands.
        assertTrue(ZakumPqData.isQuestRoom(280011005), "the ore room is part of the run");
        for (int crate : ZakumPqData.CRATES) {
            if (crate == 2112014) {
                return;
            }
        }
        throw new AssertionError("2112014 - the only Fire Ore crate - is missing from CRATES");
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
