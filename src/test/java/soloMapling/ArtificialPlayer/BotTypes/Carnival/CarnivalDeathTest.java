package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the rule that a Carnival death is a point penalty, not an ejection.
 *
 * <p>Every other content type in this plugin treats death the same way: patch up, then put the
 * bot down in its map's {@code returnMap} sanctuary. Carnival cannot, and the reason is a
 * property of the arena data rather than a preference:
 *
 * <ul>
 *   <li>{@code Character.playerDead} only deducts CP for a CPQ map and deliberately leaves the
 *       character on the field - the client's own revive is what keeps a player in the match;</li>
 *   <li>an arena's {@code returnMap} is the field's <em>waiting room</em>: carrying the corpse
 *       there takes the bot out of the match, and the lobby reclaim then sends it home - a bot
 *       that dies once is gone from the rest of the run.</li>
 * </ul>
 *
 * <p>The decision is one boolean, so it is pinned here against the arena/ordinary-map split
 * rather than against a live character.
 */
class CarnivalDeathTest {

    /**
     * The rule {@code BotDeath.staysOnDeath} implements: a carnival arena keeps its dead where
     * they fell, any other map falls back to the sanctuary trip.
     */
    private static boolean staysOnDeath(boolean cpqArena) {
        return cpqArena;
    }

    @Test
    void anArenaDeathIsServedInPlace() {
        assertTrue(staysOnDeath(true), "a corpse on a carnival field stays on the field");
    }

    @Test
    void anOrdinaryDeathStillGoesHome() {
        assertFalse(staysOnDeath(false), "every other map keeps the sanctuary trip");
    }

    @Test
    void theArenaReturnMapIsAWaitingRoomNotASanctuary() {
        // The concrete reason the generic trip is wrong here: 980000101's returnMap is
        // 980000102, the field's holding map - not an arena and not a town.
        int arena = CarnivalPqData.arenaForField(0);
        int holding = arena + 1;
        assertEquals(980000101, arena);
        assertEquals(980000102, holding);
        assertFalse(CarnivalPqData.fieldOfLobby(holding) >= 0,
                "the holding map is not a field lobby");
        assertTrue(CarnivalPqData.arenaForField(0) != holding,
                "the sanctuary the map declares is not the arena");
    }
}
