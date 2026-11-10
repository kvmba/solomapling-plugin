package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the map arithmetic {@link CarnivalArena} depends on to open a match on the field a player
 * is actually standing in.
 *
 * <p>Both editions look similar and are not: CPQ1 spaces its fields by a hundred map ids and puts
 * the arena at {@code +1}, CPQ2 spaces them by a thousand and puts the arena at {@code +100}. A
 * single "lobby + 1" formula silently opens CPQ2 matches in CPQ1's maps, and a shared field count
 * has the poll look for three fields the second edition does not ship.
 */
class CarnivalArenaLayoutTest {

    @Test
    void cpq1FieldsStepByAHundred() {
        assertEquals(980000100, CarnivalPqData.lobbyForField(0));
        assertEquals(980000101, CarnivalPqData.arenaForField(0));
        assertEquals(980000600, CarnivalPqData.lobbyForField(5));
        assertEquals(980000601, CarnivalPqData.arenaForField(5));
    }

    @Test
    void cpq2FieldsStepByAThousand() {
        // The second edition is laid out at 980031xxx / 980032xxx / 980033xxx: the arena sits a
        // hundred above the lobby, not one.
        assertEquals(980031000, CarnivalPqData.lobbyForField2(0));
        assertEquals(980031100, CarnivalPqData.arenaForField2(0));
        assertEquals(980033000, CarnivalPqData.lobbyForField2(2));
        assertEquals(980033100, CarnivalPqData.arenaForField2(2));
    }

    @Test
    void theTwoEditionsNeverShareAMap() {
        Set<Integer> seen = new HashSet<>();
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertTrue(seen.add(CarnivalPqData.lobbyForField(field)), "CPQ1 lobby repeats");
            assertTrue(seen.add(CarnivalPqData.arenaForField(field)), "CPQ1 arena repeats");
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            assertTrue(seen.add(CarnivalPqData.lobbyForField2(field)), "CPQ2 lobby repeats");
            assertTrue(seen.add(CarnivalPqData.arenaForField2(field)), "CPQ2 arena repeats");
        }
    }

    @Test
    void aLobbyIdRecoversItsOwnField() {
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertEquals(field, CarnivalPqData.fieldOfLobby(CarnivalPqData.lobbyForField(field)));
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            assertEquals(field, CarnivalPqData.fieldOfLobby(CarnivalPqData.lobbyForField2(field)));
        }
    }

    @Test
    void theSecondEditionRunsThreeFieldsNotSix() {
        // 2042001.js offers six fields; sendCPQMapLists2 loops i < 3. A poll that assumed six
        // would look for three arenas that do not exist.
        assertEquals(6, CarnivalPqData.FIELD_COUNT);
        assertEquals(3, CarnivalPqData.FIELD_COUNT_CPQ2);
        assertEquals(6, CarnivalPqData.fieldCount(false));
        assertEquals(3, CarnivalPqData.fieldCount(true));
        assertEquals(-1, CarnivalPqData.fieldOfLobby(CarnivalPqData.lobbyForField2(3)),
                "CPQ2 field 3 does not exist");
    }

    @Test
    void arenasAndTownMapsAreNotLobbies() {
        assertEquals(-1, CarnivalPqData.fieldOfLobby(980000101), "arena is not a lobby");
        assertEquals(-1, CarnivalPqData.fieldOfLobby(980000000), "the hall is not a field");
        assertEquals(-1, CarnivalPqData.fieldOfLobby(980031100), "CPQ2 arena is not a lobby");
        assertEquals(-1, CarnivalPqData.fieldOfLobby(100000000), "an ordinary town is not a lobby");
    }

    @Test
    void theSecondEditionIsRecognisableByMapRange() {
        assertTrue(CarnivalPqData.isSecondEdition(980031000));
        assertTrue(CarnivalPqData.isSecondEdition(980033400));
        assertFalse(CarnivalPqData.isSecondEdition(980000000));
        assertFalse(CarnivalPqData.isSecondEdition(980000601));
    }

    @Test
    void aBotTeamIsAFullPartyAndEveryFieldCanHostOne() {
        // Six is the party cap and every arena's maxPlayers; a team of six is never the reason a
        // match is refused.
        assertEquals(6, CarnivalPqData.MAX_TEAM_SIZE);
        assertTrue(CarnivalPqData.MAX_TEAM_SIZE >= 2, "a match needs two sides");
    }

    @Test
    void aLobbyAndItsArenaAreAlwaysDifferentMaps() {
        // MonsterCarnival force-moves both parties into the arena; if the arena id were the lobby
        // id the match would be played in the waiting room.
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT; field++) {
            assertNotEquals(CarnivalPqData.arenaForField(field),
                    CarnivalPqData.lobbyForField(field), "CPQ1 field " + field);
        }
        for (int field = 0; field < CarnivalPqData.FIELD_COUNT_CPQ2; field++) {
            assertNotEquals(CarnivalPqData.arenaForField2(field),
                    CarnivalPqData.lobbyForField2(field), "CPQ2 field " + field);
        }
    }
}
