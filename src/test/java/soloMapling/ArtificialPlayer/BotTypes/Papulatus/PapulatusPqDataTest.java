package soloMapling.ArtificialPlayer.BotTypes.Papulatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the map geometry the Papulatus bot keys its two-state life on: the lobby it waits in
 * and the one fight map it works. The event is a single-room run, so the data is two constants
 * and the predicate that must never confuse one for the other.
 *
 * <p>The failure this guards is the LPQ-bonus-stage kind: a bot that reads the lobby as "inside
 * the quest" stops recruiting (tickLobby never runs), and a bot that reads the fight map as
 * "outside" walks home mid-run. Both are silent - the lobby just looks understaffed and the
 * run just looks short one body.
 */
class PapulatusPqDataTest {

    @Test
    void theLobbyAndTheFightMapAreTheScriptsOwnMaps() {
        // Straight from PapulatusBattle.js: recruitMap 220080000, entryMap 220080001.
        assertEquals(220080000, PapulatusPqData.LOBBY_MAP);
        assertEquals(220080001, PapulatusPqData.BOSS_MAP);
    }

    @Test
    void theLobbyIsNeverInsideTheQuest() {
        // The lobby is where tickLobby runs - recruit shouts, strolls, party offers. Reading
        // it as a quest room would strand the whole cohort in silence.
        assertFalse(PapulatusPqData.isQuestRoom(PapulatusPqData.LOBBY_MAP));
    }

    @Test
    void theFightMapIsInsideTheQuest() {
        assertTrue(PapulatusPqData.isQuestRoom(PapulatusPqData.BOSS_MAP));
    }

    @Test
    void thePartyBoundsMatchTheEventScript() {
        // PapulatusBattle.js: minPlayers 1 (a solo attempt is legal), maxPlayers 6.
        assertEquals(1, PapulatusPqData.MIN_PLAYERS);
        assertEquals(6, PapulatusPqData.MAX_PLAYERS);
    }

    @Test
    void theLevelBandKeepsBotsPartyable() {
        // The floor sits at quest 7103's lvmin (80). Below 10 the party system refuses to arm
        // a bot's invite at all, so the band must never draw a sub-10 bot - and with a floor
        // of 80 it never can.
        assertTrue(PapulatusPqData.MIN_LEVEL >= 10, "sub-10 bots can never accept a party invite");
        assertTrue(PapulatusPqData.MIN_LEVEL <= PapulatusPqData.MAX_LEVEL,
                "the level band must be non-empty");
    }
}
