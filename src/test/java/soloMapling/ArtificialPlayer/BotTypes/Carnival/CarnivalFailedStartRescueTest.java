package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where a player party is put back when a carnival start FAILS.
 *
 * <p>{@code MonsterCarnival}'s constructor force-warps both parties into the disposable arena
 * before the work that can refuse the match, and every portal on an arena map carries the engine's
 * no-map sentinel. A party left there cannot walk out - the host's own three-minute escort may not
 * still be armed, and a party parked in an arena must never wait on it anyway - so a refused start
 * would strand them on a dead map.
 *
 * <p>The rescue target matters as much as the rescue: the arena's own {@code returnMap} points at
 * the field's holding map, and that map returns to the arena. A party parked there is walked
 * straight back into the map that just failed to open, so the target must be the field lobby, and
 * specifically the lobby of the field the party queued for.
 */
class CarnivalFailedStartRescueTest {

    @Test
    void theRescueTargetIsThePartysOwnFieldLobby() {
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertEquals(CarnivalPqData.lobbyForField(field),
                    CarnivalPqData.rescueMap(false, field),
                    "a CPQ1 party goes back to the lobby of the field it queued for");
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            assertEquals(CarnivalPqData.lobbyForField2(field),
                    CarnivalPqData.rescueMap(true, field),
                    "a CPQ2 party goes back to the lobby of the field it queued for");
        }
    }

    /** Fields are distinct: a party must not be moved to some other field's lobby. */
    @Test
    void everyFieldHasItsOwnRescueLobby() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertTrue(seen.add(CarnivalPqData.rescueMap(false, field)),
                    "CPQ1 field " + field + " shares a rescue lobby with another field");
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            assertTrue(seen.add(CarnivalPqData.rescueMap(true, field)),
                    "CPQ2 field " + field + " shares a rescue lobby with another field");
        }
        assertTrue(CarnivalPqData.rescueMap(false, 0) != CarnivalPqData.rescueMap(true, 0),
                "the two editions must not share a rescue map");
    }

    /**
     * The concrete reason the arena's returnMap is the wrong destination: it is the holding map,
     * which returns to the arena.
     */
    @Test
    void theArenaReturnMapWouldBounceBackIntoTheArena() {
        int arena = CarnivalPqData.arenaForField(0);
        int holding = arena + 1; // what 980000101's info/returnMap names
        assertTrue(CarnivalPqData.rescueMap(false, 0) != holding,
                "the holding map returns to the arena - a party parked there is walked back in");
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            int lobby = CarnivalPqData.rescueMap(false, field);
            assertFalse(CarnivalPqData.isArenaId(lobby),
                    "the rescue target must not itself be an arena");
            assertTrue(CarnivalPqData.fieldOfLobby(lobby) >= 0,
                    "the rescue target must be a field lobby");
            assertFalse(CarnivalPqData.isHall(lobby),
                    "the party waits in a field lobby, not the town hall");
        }
    }
}
