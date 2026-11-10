package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the size of the bot side in a Monster Carnival match.
 *
 * <p>The arena used to field a full six against anybody, so a two-player party fought a side three
 * times its size - the reported "the bot team is always full". The opposing team is now one body
 * smaller than the player side, with a lone player still getting an opponent; the whole roster
 * counts (including a bot the player recruited in the hall), because that is the side the match is
 * actually fought with.
 */
class CarnivalBotTeamSizeTest {

    @Test
    void theOpponentIsOneSmallerThanThePlayerParty() {
        for (int players = 2; players <= CarnivalPqData.MAX_TEAM_SIZE; players++) {
            assertEquals(players - 1, CarnivalPqData.botTeamSizeFor(players),
                    players + " players should be met by " + (players - 1) + " bots");
        }
    }

    @Test
    void aLonePlayerStillGetsAnOpponent() {
        // "One fewer" alone would size a one-player party's opponent at zero, i.e. no match at all.
        assertEquals(1, CarnivalPqData.botTeamSizeFor(1));
        assertEquals(1, CarnivalPqData.botTeamSizeFor(0),
                "an empty roster must not ask for zero bots");
        assertEquals(1, CarnivalPqData.botTeamSizeFor(-3));
        assertEquals(5, CarnivalPqData.botTeamSizeFor(CarnivalPqData.MAX_TEAM_SIZE),
                "the biggest player party still gets a full match");
    }

    /**
     * The draft must be asked for the sized side, not left to fill the party cap.
     *
     * <p>The count is threaded through the whole join path - the poll sizes it from the party
     * standing in the lobby, {@code botTeamFor} rebuilds a team of the wrong size, and the draft
     * tops up to it - so a source pin here guards the one place where the full-team cap could
     * silently come back. The branches need a live channel to execute, which no unit test can
     * supply.
     */
    @Test
    void theJoinPathAsksForTheSizedTeam() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Carnival/CarnivalArena.java"));

        int deadline = src.indexOf("if (now < waiting.joinAtMs)");
        assertTrue(deadline > 0, "the join must stay gated on the rolled deadline");
        int dueBlockEnd = src.indexOf("if (waiting.clockAtMs != 0L)", deadline);
        String dueBlock = src.substring(deadline, dueBlockEnd);
        assertTrue(dueBlock.contains("CarnivalPqData.botTeamSizeFor("),
                "the team is sized from the party standing in the lobby");
        assertTrue(dueBlock.contains("botTeamFor(channel, field, cpq2, want)"),
                "the sized count must reach the team builder");

        assertTrue(src.contains("existing.getPartyMembers().size() == want"),
                "a team of the previous party's size must be rebuilt, not reused");
        assertTrue(src.contains("bots.size() < want"),
                "recruiting must fail short of the sized side, not of a full six");
        assertTrue(src.contains("requestTeamSpawn(channel, field, cpq2, want - out.size())"),
                "a top-up only asks for the bodies still missing");
        assertFalse(src.contains("out.size() > CarnivalPqData.MAX_TEAM_SIZE"),
                "the draft must not cap its pick at the party cap again");
    }

    /**
     * The count must not be re-derived at the deadline.
     *
     * <p>A party that is still filling up (a teammate walks in, or recruits a bot in the hall)
     * keeps changing during the ten-second count. Re-sizing the opponent at the end of it would
     * refuse the team already standing in the lobby and re-roll its wait - the players would see
     * the opponent appear, vanish, and come back a minute later. The side is therefore sized once,
     * when it walks in.
     */
    @Test
    void theSideIsNotReSizedAtTheDeadline() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Carnival/CarnivalArena.java"));
        int open = src.indexOf("private static boolean openMatch(");
        assertTrue(open > 0, "openMatch must exist");
        int bodyEnd = src.indexOf("private static void rescuePlayersFromArena(", open);
        String body = src.substring(open, bodyEnd);
        assertFalse(body.contains("botTeamSizeFor("),
                "the match must not re-size the side that is already in the lobby");
    }
}
