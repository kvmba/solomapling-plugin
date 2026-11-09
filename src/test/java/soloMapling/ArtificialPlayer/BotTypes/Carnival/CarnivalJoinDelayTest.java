package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the hesitation before an opposing bot team walks into a field.
 *
 * <p>The poll used to field the team in the same tick it first saw a party, so six bots popped
 * into the field the instant a player walked in - the reported "the bot team joins too fast".
 * A player-visible opponent is supposed to be another party that was queuing too, so the join is
 * now deadline-driven and rolled between {@link CarnivalArena#JOIN_DELAY_MIN_MS} and
 * {@link CarnivalArena#JOIN_DELAY_MAX_MS}. Nothing else about the count changes: once the team is
 * in, the host's ten-second clock runs as before.
 */
class CarnivalJoinDelayTest {

    private static final java.nio.file.Path ARENA = java.nio.file.Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Carnival/CarnivalArena.java");

    /**
     * The poll's join branch must not field the team where the wait is armed.
     *
     * <p>This is the actual regression that mattered: the first cut of the delay kept
     * {@code botTeamFor}/{@code moveTeamTo} in the "a new party showed up" step, so the team still
     * walked in on the same tick and the roll was only ever consumed afterwards. Pinned at the
     * source because the branch needs a live channel, map and party to execute, which no unit
     * test can supply; the shape of the branch is the contract.
     */
    @Test
    void theTeamIsFieldedAtTheDeadlineNotWhenTheWaitIsArmed() throws Exception {
        String src = java.nio.file.Files.readString(ARENA);
        int arm = src.indexOf("PENDING.put(key, new PendingMatch(players, lobby, now + rollJoinDelayMs()));");
        assertTrue(arm > 0, "the wait must be armed with a rolled join deadline");
        int armBlockStart = src.lastIndexOf("if (waiting == null || waiting.players != players)", arm);
        assertTrue(armBlockStart > 0, "the arm must sit in the new-party branch");
        String armBlock = src.substring(armBlockStart, src.indexOf("if (waiting.bots == null)", arm));
        assertFalse(armBlock.contains("botTeamFor("),
                "the opposing team must not be drafted where the wait is armed");
        assertFalse(armBlock.contains("moveTeamTo("),
                "the opposing team must not be walked in where the wait is armed");

        int deadline = src.indexOf("if (now < waiting.joinAtMs)");
        assertTrue(deadline > arm, "the join must be gated on the rolled deadline");
        int dueBlockEnd = src.indexOf("if (waiting.clockAtMs != 0L)", deadline);
        String dueBlock = src.substring(deadline, dueBlockEnd);
        assertTrue(dueBlock.contains("botTeamFor(") && dueBlock.contains("moveTeamTo("),
                "the team is drafted and walked in only once the deadline expires");
    }

    @Test
    void theJoinDelayIsARolledTenToSixtySeconds() {
        assertEquals(10_000L, CarnivalArena.JOIN_DELAY_MIN_MS);
        assertEquals(60_000L, CarnivalArena.JOIN_DELAY_MAX_MS);

        // The roll is uniform over the window, so samples must land inside it - and must not all
        // land on one value, or the "random" wait would be a fixed one.
        long first = CarnivalArena.rollJoinDelayMs();
        boolean varied = false;
        for (int i = 0; i < 200; i++) {
            long roll = CarnivalArena.rollJoinDelayMs();
            assertTrue(roll >= CarnivalArena.JOIN_DELAY_MIN_MS
                            && roll <= CarnivalArena.JOIN_DELAY_MAX_MS,
                    "rolled " + roll + "ms, outside the stated window");
            varied |= roll != first;
        }
        assertTrue(varied, "every roll returned the same wait: the join is not random");
    }

    @Test
    void thePartyWaitsAloneUntilTheJoinIsDue() throws Exception {
        // The wait record carries the deadline, and the team field starts empty: the poll must be
        // able to distinguish "opponent on its way" from "opponent already here" without a second
        // map of its own.
        ClassLoader loader = CarnivalJoinDelayTest.class.getClassLoader();
        Class<?> pending = Class.forName(
                "soloMapling.ArtificialPlayer.BotTypes.Carnival.CarnivalArena$PendingMatch",
                false, loader);
        Field joinAt = pending.getDeclaredField("joinAtMs");
        Field bots = pending.getDeclaredField("bots");
        assertTrue(Modifier.isFinal(joinAt.getModifiers()),
                "the deadline is rolled once, when the party is first seen");
        assertFalse(Modifier.isFinal(bots.getModifiers()),
                "the team is attached later, when the deadline expires");
        // The record is built before any team exists, so its constructor cannot take one.
        assertNotNull(pending.getDeclaredConstructor(
                Class.forName("org.gms.net.server.world.Party", false, loader),
                Class.forName("org.gms.server.maps.MapleMap", false, loader),
                long.class));
    }

    @Test
    void theWaitStageDoesNotTouchTheHostsThreeMinuteClock() throws Exception {
        // dropPending clears OUR ten-second clock. While the party is still waiting alone for its
        // opponent, no clock of ours was ever sent - the client is showing the host's three-minute
        // escort (cpqLobby) - so clearing it there would leave a player with no clock at all while
        // an invisible three-minute warp still counts down. `dueAtMs` is armed exactly when the
        // ten-second clock goes out, so it is the marker the clear keys on.
        ClassLoader loader = CarnivalJoinDelayTest.class.getClassLoader();
        Class<?> pending = Class.forName(
                "soloMapling.ArtificialPlayer.BotTypes.Carnival.CarnivalArena$PendingMatch",
                false, loader);
        var ctor = pending.getDeclaredConstructor(
                Class.forName("org.gms.net.server.world.Party", false, loader),
                Class.forName("org.gms.server.maps.MapleMap", false, loader),
                long.class);
        ctor.setAccessible(true);
        var clockSent = pending.getDeclaredMethod("clockSent");
        clockSent.setAccessible(true);
        var dueAt = pending.getDeclaredField("dueAtMs");
        dueAt.setAccessible(true);

        Object fresh = ctor.newInstance(null, null, System.currentTimeMillis() + 30_000L);
        assertEquals(Boolean.FALSE, clockSent.invoke(fresh),
                "a freshly armed wait must not claim its ten-second clock was sent");

        // The clock-send step is the ONLY writer of the deadline; simulate it and the clear must
        // then agree that our clock is on screen.
        dueAt.setLong(fresh, System.currentTimeMillis() + CarnivalPqData.MATCH_WAIT_MS);
        assertEquals(Boolean.TRUE, clockSent.invoke(fresh),
                "once the count has gone out its clock is ours to clear");
    }

    @Test
    void theHostsThreeMinuteEscortStillOutlastsTheWait() {
        // cpqLobby warps a party out of the field three minutes after it enters. The rolled join
        // plus the host's ten-second count must still finish well inside that window, or the
        // party would be escorted out of a match that is about to start.
        long worstCase = CarnivalArena.JOIN_DELAY_MAX_MS
                + CarnivalPqData.CLOCK_DELAY_MS
                + CarnivalPqData.MATCH_WAIT_MS;
        assertTrue(worstCase < 180_000L,
                "a full wait (" + worstCase + "ms) must end before the host's 3-minute escort");
    }
}
