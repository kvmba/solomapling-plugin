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
    void aBotWorkingTheRunReDecidesFast() throws Exception {
        String body = methodBody(source(), "protected long lowPriorityDelayMs()");
        assertTrue(body.contains("isInsideQuest"),
                "the fast cadence is for the run, not for idling in town");
        assertTrue(body.contains("getMap().getEventInstance() != null"),
                "the fast cadence is for maps that belong to the run's instance");
        assertTrue(body.contains("IN_RUN_DELAY_MS"), "the in-run pace must be its own constant");
        assertTrue(body.contains("super.lowPriorityDelayMs()"),
                "a bot outside the run keeps the ordinary cadence exactly");
    }

    /**
     * A recruit lobby must not be pushed onto the fast wheel. Three quests list a public map in
     * {@code isInsideQuest}: BossRush's host map (970030000, which is also its recruit point),
     * Carnival's two lobbies and Ariant's coliseum lobby. Those maps carry no instance, so the
     * instance gate is what keeps a hall full of idle runners on the background cadence.
     *
     * <p>The MAP's instance is the right one to read, not the character's: a room-to-room walk
     * unregisters and re-registers the character mid-warp, so the character's field is null for a
     * moment exactly when a returning bot decides its next move - which would drop it back onto
     * the 36-48s wheel at the very moment this fix exists for.
     */
    @Test
    void aPublicLobbyMapKeepsTheSlowBackgroundCadence() throws Exception {
        String body = methodBody(source(), "protected long lowPriorityDelayMs()");
        assertTrue(body.contains("getMap().getEventInstance()"),
                "isInsideQuest alone is not enough - it contains public lobby maps, and the "
                        + "pace would turn every empty recruit hall into a busy one");
        assertFalse(body.contains("getChr().getEventInstance()"),
                "the character's instance is nulled during a room-to-room warp; read the map's");
        assertFalse(body.contains("partyLeader"),
                "the pace must not depend on where the leader is: a bot back on the leader's "
                        + "map (or a leader that resolves no Character) is still inside the run");
    }

    @Test
    void catchUpPaceBeatsTheUnobservedDefault() throws Exception {
        String java = source();
        long inRun = Long.parseLong(
                java.replaceAll("(?s).*IN_RUN_DELAY_MS = ([0-9_]+)L;.*", "$1").replace("_", ""));
        // BotSM's unobserved cadence is 36_000 + rand(12_000).
        assertTrue(inRun <= 4_000,
                "a bot working the run must re-decide in seconds, not on the 36-48s wheel; got "
                        + inRun);
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
