package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the two properties that decide whether a straggler ever catches up: one tick may chain
 * several doors, and the cadence it does that on is the observed one, not the 36-48s unobserved
 * one.
 *
 * <p>Why this is a contract test and not only a behaviour test: both numbers are policy, and both
 * were wrong in the same direction - one door per tick, on a cadence chosen for a bot nobody is
 * watching. A lagging bot is by definition on a map no player is watching, so the two defects
 * multiplied into "one room per 36-48 seconds" against a party advancing one room per stage.
 */
class PartyQuestCatchUpCadenceTest {

    @Test
    void aTickMayChainSeveralDoors() throws Exception {
        String body = methodBody(source(), "protected final void tickPartyQuest()");
        assertTrue(body.contains("MAX_CATCHUP_HOPS"),
                "the follow beat must be allowed to chain doors inside one tick");
        assertTrue(body.matches("(?s).*for \\(int hop = 0; hop < MAX_CATCHUP_HOPS; hop\\+\\+\\).*"),
                "the chain must be a bounded loop over followLeaderIntoNextRoom");
        assertFalse(body.contains("if (followLeaderIntoNextRoom())"),
                "a single hop per tick is the defect this replaced");
    }

    @Test
    void theChainIsBounded() throws Exception {
        String java = source();
        assertTrue(java.matches("(?s).*MAX_CATCHUP_HOPS = \\d+;.*"),
                "an unbounded chain could walk the whole stage chain in one tick");
        int hops = Integer.parseInt(java.replaceAll("(?s).*MAX_CATCHUP_HOPS = (\\d+);.*", "$1"));
        assertTrue(hops >= 2 && hops <= 4,
                "a few rooms per tick closes a real deficit without spanning the quest; got " + hops);
    }

    @Test
    void aBehindBotReDecidesAtTheObservedCadence() throws Exception {
        String body = methodBody(source(), "protected long lowPriorityDelayMs()");
        assertTrue(body.contains("isInsideQuest"),
                "the fast cadence is for catching up INSIDE a run, not for idling in town");
        assertTrue(body.contains("leader.getMapId() != bot.getMapId()"),
                "the fast cadence applies only while this bot is behind the leader");
        assertTrue(body.contains("CATCHUP_DELAY_MS"), "the catch-up pace must be its own constant");
        assertTrue(body.contains("super.lowPriorityDelayMs()"),
                "a bot that is not behind keeps the ordinary cadence exactly");
    }

    @Test
    void catchUpPaceBeatsTheUnobservedDefault() throws Exception {
        String java = source();
        long catchUp = Long.parseLong(
                java.replaceAll("(?s).*CATCHUP_DELAY_MS = ([0-9_]+)L;.*", "$1").replace("_", ""));
        // BotSM's unobserved cadence is 36_000 + rand(12_000).
        assertTrue(catchUp <= 4_000,
                "catching up has to be much faster than the 36-48s unobserved wheel; got " + catchUp);
    }

    @Test
    void theForwardDoorIsStillTheQuestsOwnNext00() {
        Portal next = mock(Portal.class);
        when(next.getName()).thenReturn("next00");
        when(next.getTargetMapId()).thenReturn(922010400);
        Portal decoy = mock(Portal.class);
        when(decoy.getName()).thenReturn("sp");
        when(decoy.getTargetMapId()).thenReturn(999999999);
        Collection<Portal> portals = new ArrayList<>(List.of(decoy, next));
        assertEquals(next, PartyQuestBot.forwardPortalOf(portals));
        assertFalse(PartyQuestBot.forwardPortalOf(List.of(decoy)) != null,
                "a spawn point whose target is the no-map sentinel is not a stage door");
    }

    private static String source() throws Exception {
        try (var in = PartyQuestCatchUpCadenceTest.class.getClassLoader()
                .getResourceAsStream("soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java");
             var raw = in != null ? in
                     : new java.io.FileInputStream(
                             "src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java")) {
            return new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String methodBody(String java, String signature) {
        int start = java.indexOf(signature);
        assertTrue(start > 0, "method must exist: " + signature);
        int open = java.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < java.length(); i++) {
            char c = java.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return java.substring(open, i);
        }
        throw new IllegalStateException("unbalanced method body: " + signature);
    }
}
