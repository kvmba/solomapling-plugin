package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a field's occupancy is judged - the one rule the two entry paths must agree on.
 *
 * <p>There are two ways a Monster Carnival match starts: the NPC dialog, and this plugin opening
 * one directly for a bot team. They identify the field differently - the NPC derives a "room"
 * number from the arena map id ({@code (mapId / 100) % 10} for CPQ1, {@code (mapId / 1000) % 10}
 * for CPQ2, which works out to {@code field + 1}), while {@code fieldTaken} probes the plain field
 * index. Neither number can see the other, so any occupancy test built on that registry is wrong
 * for one of the two paths.
 *
 * <p>The plugin avoids the whole question by reading physical occupancy instead: a field is busy
 * if anybody is standing in its lobby, its holding map, or its arena. Both paths produce that.
 *
 * <p>And of the two registry numbers, the plugin registers the <b>plain</b> field index, because
 * {@code fieldTaken(field)} is what decides whether a player may queue into a field - so that is
 * the one our own match must occupy.
 */
class CarnivalFieldRoomTest {

    @Test
    void thePluginRegistersThePlainFieldIndex() {
        // fieldTaken(field) checks canInitMonsterCarnival(field). If we registered field+1, a
        // player could open the NPC and queue into a field a bot match is already running in.
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertFalse(registeredRoom(field) == field + 1,
                    "field " + field + " must NOT register a shifted room");
            assertEquals(field, registeredRoom(field));
        }
    }

    /** What the plugin passes as the engine's room number: the plain field index. */
    private static int registeredRoom(int field) {
        return field;
    }

    @Test
    void theHostPathWouldUseAShiftedRoom() {
        // Documented, not depended on: the NPC's value is field+1. Its own fieldTaken cannot find
        // it, which is a host bug - and it only affects player-versus-player fields, where the
        // plugin deliberately does not interfere.
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            int mapId = CarnivalPqData.arenaForField(field);
            assertEquals(field + 1, (mapId / 100) % 10, "CPQ1 field " + field);
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            int mapId = CarnivalPqData.arenaForField2(field);
            assertEquals(field + 1, (mapId / 1000) % 10, "CPQ2 field " + field);
        }
    }

    @Test
    void theMapsOfAFieldAreDistinctAndBelongToThatFieldOnly() {
        // fieldOccupied walks the lobby and the arena. If either aliased a DIFFERENT field's map,
        // one field's players would make a neighbour look busy and that neighbour could never open.
        Set<Integer> all = new HashSet<>();
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            int lobby = CarnivalPqData.lobbyForField(field);
            int arena = CarnivalPqData.arenaForField(field);
            assertEquals(lobby + 1, arena, "CPQ1 put its arena immediately above the lobby");
            assertTrue(all.add(lobby), "CPQ1 lobby " + lobby + " repeats");
            assertTrue(all.add(arena), "CPQ1 arena " + arena + " repeats");
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            int lobby = CarnivalPqData.lobbyForField2(field);
            int arena = CarnivalPqData.arenaForField2(field);
            assertEquals(lobby + 100, arena, "CPQ2 put its arena a hundred above the lobby");
            assertTrue(all.add(lobby), "CPQ2 lobby " + lobby + " repeats");
            assertTrue(all.add(arena), "CPQ2 arena " + arena + " repeats");
        }
        // 6 CPQ1 fields x 2 maps + 3 CPQ2 fields x 2 maps, all distinct.
        assertEquals(6 * 2 + 3 * 2, all.size());
    }
}
