package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTypes.Ariant.AriantPqData;
import soloMapling.ArtificialPlayer.BotTypes.BossRush.BossRushPqData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two lobby-contract fixes that decide whether a recruit hall can actually recruit.
 *
 * <p>Both were the same mistake in different shapes: a map that a player walks into to assemble
 * a group was being read as "inside the run", which turns off every recruit path
 * ({@code awaitingRecruit()} is {@code !isInsideQuest(...)}) - the crowd stands there and cannot
 * be reached except by right-click invite.
 *
 * <p>Deliberately arithmetic-only. The engine's Ariant map tests live in {@code GameConstants},
 * whose static initializer reads WZ; asserting through them would make this test need a WZ tree
 * to answer "which arena is this bot in", so the checks here use the pure id arithmetic that
 * {@code AriantPqData} exposes for exactly that purpose.
 */
class PqLobbyRecruitabilityTest {

    // =========================================================================
    // Boss Rush: the host map is a lobby, not a run room
    // =========================================================================

    /**
     * The host map is the quest's own {@code recruitMap} <em>and</em> {@code exitMap}. It is the
     * room a player walks into to assemble a party, so it must not read as in-run - otherwise the
     * bots the spawner places there have no recruit behaviour at all.
     */
    @Test
    void theBossRushHostMapIsNotPartOfTheRun() {
        assertFalse(BossRushPqData.isFightRoom(BossRushPqData.HOST_MAP),
                "the host map is not a fighting room");
        assertFalse(BossRushPqData.isRestSpot(BossRushPqData.HOST_MAP),
                "the host map is not a resting spot");
    }

    /** The rooms the run really plays in stay inside the quest. */
    @Test
    void theBossRushFightRoomsStayInsideTheRun() {
        assertTrue(BossRushPqData.isFightRoom(BossRushPqData.FIRST_STAGE));
        assertTrue(BossRushPqData.isFightRoom(BossRushPqData.LAST_STAGE));
        assertTrue(BossRushPqData.isRestSpot(970032700));
    }

    /**
     * The spawner must not seat a bot below the host's own party floor: a level-1..9 character
     * cannot be invited at all ({@code PartyOperationHandler} refuses the invite), so those
     * bodies would be decoration.
     */
    @Test
    void noRecruitLobbySeatsABotItCannotPartyInvite() {
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            assertTrue(point.minLevel() >= 10,
                    point.name() + " seats level " + point.minLevel()
                            + " bots, below the host's party-invite floor of 10");
        }
    }

    // =========================================================================
    // Ariant: the public lobby is a lobby, the queue halls are the run
    // =========================================================================

    /**
     * Ariant has no party-based recruit path at all (the engine refuses to start a match while
     * any competitor is in a party), so the public lobby has to stay outside
     * {@code isInsideQuest} for its crowd to shout, stroll and answer greetings.
     */
    @Test
    void theAriantPublicLobbyIsNotAQueueHall() {
        assertFalse(AriantPqData.isQueueLobby(AriantPqData.LOBBY_MAP),
                "the public lobby is where a crowd is recruited, not a queue hall");
        assertEquals(-1, AriantPqData.queueIndex(AriantPqData.LOBBY_MAP));
        assertEquals(-1, AriantPqData.arenaEdition(AriantPqData.LOBBY_MAP));
    }

    /**
     * The three queue halls are part of the run: an expedition waits there for its last member,
     * and a bot that read them as "outside the quest" would walk home mid-registration.
     */
    @Test
    void theAriantQueueHallsArePartOfTheRun() {
        for (int edition = 0; edition < 3; edition++) {
            int map = AriantPqData.queueMapFor(edition);
            assertTrue(AriantPqData.isQueueLobby(map), map + " must be a queue hall");
            assertEquals(edition, AriantPqData.queueIndex(map));
        }
        assertFalse(AriantPqData.isQueueLobby(980010010), "the kings room is not a queue hall");
        assertFalse(AriantPqData.isQueueLobby(980010001), "the arena is not a queue hall");
        assertEquals(-1, AriantPqData.queueMapFor(3));
        assertEquals(-1, AriantPqData.queueMapFor(-1));
    }

    /**
     * Each arena's edition is read off its own id, and the three arenas must map to the three
     * queue halls in the same order the queue NPC switches on (980010101 -> edition 0, etc).
     */
    @Test
    void eachArenaMapsToItsOwnEdition() {
        for (int edition = 0; edition < 3; edition++) {
            int arena = AriantPqData.queueMapFor(edition) + 1;
            assertEquals(edition, AriantPqData.arenaEdition(arena),
                    "arena " + arena + " must be edition " + edition);
        }
        assertEquals(-1, AriantPqData.arenaEdition(980010010), "the kings room is not an arena");
        assertEquals(-1, AriantPqData.arenaEdition(980010100), "a queue hall is not an arena");
        assertEquals(-1, AriantPqData.arenaEdition(AriantPqData.LOBBY_MAP));
    }

    /**
     * The lobby row is what puts the crowd there in the first place; without it the spawner has
     * no Ariant entry and the hall stays empty however good the bot is.
     */
    @Test
    void theAriantLobbyIsSpawnedAtTheExpeditionsOwnLevels() {
        PqRecruitPoints.Point point = PqRecruitPoints.byName("AriantPQ");
        assertTrue(point != null, "Ariant has no recruit point row");
        assertEquals(AriantPqData.LOBBY_MAP, point.recruitMap());
        assertEquals(AriantPqData.MIN_LEVEL, point.minLevel());
        assertEquals(AriantPqData.MAX_LEVEL, point.maxLevel());
        assertEquals(AriantPqData.MAX_MEMBERS, point.maxPlayers());
    }
}
