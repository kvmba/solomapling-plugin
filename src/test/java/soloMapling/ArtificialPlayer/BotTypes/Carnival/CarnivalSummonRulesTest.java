package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the two facts about Monster Carnival that a bot gets wrong quietly.
 *
 * <p>CPQ is the one content type here where the bot is an opponent rather than a helper, so
 * there is no player feedback to correct it - nobody notices a second team that plays badly,
 * they just win. That makes the cheap mistakes worth pinning: a summon the bot cannot afford,
 * or one it has run out of allowance for, is refused by the server and looks from the inside
 * like a bot that simply stopped playing.
 */
class CarnivalSummonRulesTest {

    @Test
    void aSummonNeedsBothThePointsAndTheAllowance() {
        // The engine checks CP against the entry's cost and the side's remaining summons.
        // Missing either check makes the bot send requests the server refuses.
        assertTrue(CarnivalPqData.canSummon(100, 50, true), "affordable and allowed");
        assertFalse(CarnivalPqData.canSummon(100, 50, false), "no summons left this side");
        assertFalse(CarnivalPqData.canSummon(10, 50, true), "cannot afford it");
        assertTrue(CarnivalPqData.canSummon(50, 50, true), "exact points should be enough");
    }

    @Test
    void aFreeSummonIsNotASummon() {
        // A cost of zero or less is malformed data, not a bargain: treating it as affordable
        // would let the bot spam monsters the arena never priced.
        assertFalse(CarnivalPqData.canSummon(100, 0, true));
        assertFalse(CarnivalPqData.canSummon(100, -5, true));
    }

    @Test
    void theTwoVersionsCoverTheirLevelBands() {
        // CPQ1 is 30-50 and CPQ2 is 51-70; the bot picks a lobby by level, and a level outside
        // both bands cannot enter at all.
        assertTrue(CarnivalPqData.levelEligible(30));
        assertTrue(CarnivalPqData.levelEligible(50));
        assertTrue(CarnivalPqData.levelEligible(51));
        assertTrue(CarnivalPqData.levelEligible(70));
        assertFalse(CarnivalPqData.levelEligible(29));
        assertFalse(CarnivalPqData.levelEligible(71));
    }

    @Test
    void eachLevelBandIsSentToItsOwnLobby() {
        // The lobbies are separate maps, so sending a 60 to CPQ1's lobby strands them in a
        // room whose matches they cannot enter.
        assertTrue(CarnivalPqData.lobbyForLevel(40) == CarnivalPqData.LOBBY_MAP);
        assertTrue(CarnivalPqData.lobbyForLevel(60) == CarnivalPqData.LOBBY_MAP_CPQ2);
        // The band boundary: 51 is CPQ2's first level, so it must not land in CPQ1's lobby.
        assertTrue(CarnivalPqData.lobbyForLevel(51) == CarnivalPqData.LOBBY_MAP_CPQ2);
        assertTrue(CarnivalPqData.lobbyForLevel(50) == CarnivalPqData.LOBBY_MAP);
    }
}
