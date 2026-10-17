package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.server.maps.GenericPortal;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the catch-up half of the follow contract: a bot that had its own work to finish in a
 * stage (LPQ's trap room, a door room) leaves that stage one room late, and must still rejoin the
 * party - not sit at the finished stage's NPC for the rest of the run.
 *
 * <p>The rule under test is the PORTAL CHOICE, kept pure here (a real {@code MapleMap} cannot be
 * built in a unit test): when the leader is more than one room ahead there is no portal whose
 * target IS the leader's map, and the bot must then walk the quest's own forward door - the same
 * {@code next00} door a player uses - whose script re-checks the stage gate so the bot can never
 * skip ahead of the leader.
 */
class PartyQuestCatchUpDoorTest {

    private static Portal portal(int id, String name, int targetMap, int x, int y) {
        return new Stub(id, name, targetMap, new Point(x, y));
    }

    private static final class Stub extends GenericPortal {
        private final int id;
        private final String name;
        private final int targetMap;
        private final Point pos;

        Stub(int id, String name, int targetMap, Point pos) {
            super(0);
            this.id = id;
            this.name = name;
            this.targetMap = targetMap;
            this.pos = pos;
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getTargetMapId() {
            return targetMap;
        }

        @Override
        public Point getPosition() {
            return pos;
        }
    }

    /** The real LPQ stage-2 tower: spawn, tower and the next00 chain door. */
    @Test
    void aBotOneStageBehindHasNoDirectDoorButDoesHaveTheChainDoor() {
        Portal spawn = portal(0, "sp", 999999999, -177, -2635);
        Portal towerSpawn = portal(1, "st00", 999999999, -192, -2523);
        Portal next = portal(2, "next00", 922010300, 94, -2643);

        // The leader is two rooms ahead (stage 4): no portal targets his map.
        assertEquals(null, PartyQuestBot.portalTo(List.of(spawn, towerSpawn, next), 922010400),
                "no direct door exists - this is the situation the catch-up walk handles");

        // The chain door is the forward door of this room, and it only ever advances one stage:
        // its script (lpq1) refuses unless 2stageclear is set.
        Portal forward = PartyQuestBot.forwardPortalOf(List.of(spawn, towerSpawn, next));
        assertSame(next, forward, "next00 is the room's forward door");
        assertEquals(922010300, forward.getTargetMapId(), "the chain door advances exactly one stage");
    }

    /** The trap room (922010201) has only its own exit - it is not on the chain. */
    @Test
    void theTrapRoomHasNoForwardDoor() {
        Portal spawn = portal(0, "sp", 999999999, -186, -267);
        Portal st = portal(1, "st00", 999999999, 1, -224);
        Portal out = portal(2, "out00", 922010200, -157, -3888);

        assertEquals(null, PartyQuestBot.forwardPortalOf(List.of(spawn, st, out)),
                "the trap room exits back to its own stage, it does not advance the chain");
    }

    /** A next00 that is a script-only gate (no target map) must not be walked as a catch-up door. */
    @Test
    void aScriptOnlyNext00IsNotAForwardDoor() {
        Portal spawn = portal(0, "sp", 999999999, 0, 0);
        // Davy Jones' chain doors are named next00 but carry the no-map sentinel: their effect is
        // the room's script, not a stage transition.
        Portal scriptGate = portal(2, "next00", 999999999, 100, 0);
        assertEquals(null, PartyQuestBot.forwardPortalOf(List.of(spawn, scriptGate)),
                "a script-only next00 is not a stage door");
    }

    /** A room with no next00 at all must report no forward door rather than walk somewhere random. */
    @Test
    void aRoomWithoutAChainDoorReportsNone() {
        Portal spawn = portal(0, "sp", 999999999, 0, 0);
        assertEquals(null, PartyQuestBot.forwardPortalOf(List.of(spawn)));
        assertEquals(null, PartyQuestBot.forwardPortalOf(null));
    }
}
