package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where a short field's replacement bots are SPAWNED.
 *
 * <p>A bot created for a top-up has no party yet, and {@code CarnivalPQBot.lobbyMapId} only treats
 * a field lobby as home while the bot belongs to a party waiting there:
 *
 * <pre>
 *   if (CarnivalPqData.fieldOfLobby(mapId) &gt;= 0 &amp;&amp; getChr().getParty() != null) return mapId;
 * </pre>
 *
 * <p>Spawning the replacements INTO the field lobby therefore made each fresh bot reclaim itself
 * to the hall on its very first tick - the field kept waiting for six bots it had already been
 * given, and every top-up paid a pointless cross-map move. The draft scans the whole channel's
 * storage ({@code recruitBots}), so a hall body is picked up exactly like a field one.
 */
class CarnivalTopUpSpawnMapTest {

    @Test
    void aTopUpGoesToTheHallOfItsEdition() {
        assertEquals(CarnivalPqData.LOBBY_MAP, CarnivalPqData.topUpSpawnMap(false),
                "CPQ1 replacements belong in the CPQ1 hall");
        assertEquals(CarnivalPqData.LOBBY_MAP_CPQ2, CarnivalPqData.topUpSpawnMap(true),
                "CPQ2 replacements belong in the CPQ2 hall");
        assertTrue(CarnivalPqData.topUpSpawnMap(false) != CarnivalPqData.topUpSpawnMap(true),
                "the two editions must not share a spawn map");
    }

    /**
     * The whole point: the target must NOT be a field lobby, because a party-less bot does not
     * treat one as home.
     */
    @Test
    void theTopUpTargetIsNotAFieldLobby() {
        for (boolean cpq2 : new boolean[]{false, true}) {
            int target = CarnivalPqData.topUpSpawnMap(cpq2);
            assertTrue(CarnivalPqData.fieldOfLobby(target) < 0,
                    "a top-up must not land in a field lobby: a party-less bot reclaims itself home");
            assertTrue(CarnivalPqData.isHall(target),
                    "it must land in a hall, which is where a free carnival bot waits");
        }
    }

    /** A field lobby is still a carnival map, so the draft can see bots that ARE waiting there. */
    @Test
    void aFieldLobbyStillCountsForTheDraft() {
        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.lobbyForField(0)));
        assertTrue(CarnivalPqData.isCarnivalMap(CarnivalPqData.topUpSpawnMap(false)));
    }
}
