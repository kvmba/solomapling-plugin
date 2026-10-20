package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.server.maps.GenericPortal;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the scripted stage-door declarations - the third way a quest advances a room.
 *
 * <p>Several quests (Pirate, Ellin, Magatia, Boss Rush, Horntail) advance through portals whose
 * WZ target is the no-map sentinel and whose effect lives in the portal script. Those doors are
 * invisible to destination matching and excluded from the {@code next00} rule, so before the
 * per-quest declarations a bot standing in one of those rooms never attempted a room change at
 * all: it stayed behind while the party moved on. Each test below pins one quest's real WZ door
 * (name, target, script, transcribed from the map img.xml) against
 * {@link PartyQuestBot#scriptedPortalOf}, and the refusal cases that keep the declaration from
 * walking a door that has been re-pointed or relinked.
 */
class PartyQuestScriptedDoorTest {

    private static Portal portal(int id, String name, int targetMap, String script, int x, int y) {
        Stub stub = new Stub(id, name, targetMap, new Point(x, y));
        stub.setScriptName(script);
        return stub;
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

    /** Pirate 925100100: next00 / 999999999 / davy_next1 (Map9/925100100.img.xml). */
    @Test
    void thePirateChainDoorIsTheScriptedNext00() {
        Portal spawn = portal(0, "sp", 999999999, null, -1717, 151);
        Portal out = portal(1, "out00", 251010401, null, -1859, 241);
        Portal next = portal(2, "next00", 999999999, "davy_next1", 1490, 80);

        assertSame(next, PartyQuestBot.scriptedPortalOf(List.of(spawn, out, next), "next00", null),
                "davy_next1's next00 is the chain door");
        // A door re-pointed at a real map is no longer the script gate the declaration was
        // written for - the next00 rule (forwardPortalOf) owns those instead.
        Portal repointed = portal(2, "next00", 925100200, "davy_next1", 1490, 80);
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(spawn, out, repointed), "next00", null),
                "a next00 with a real target is a chain door, not a scripted one");
    }

    /** Ellin 930000000: east00 / 999999999 / party6_stage (Map9/930000000.img.xml). */
    @Test
    void theEllinChainDoorIsParty6Stage() {
        Portal spawn = portal(0, "sp", 999999999, null, 0, 0);
        Portal east = portal(4, "east00", 999999999, "party6_stage", 100, 0);

        assertSame(east, PartyQuestBot.scriptedPortalOf(List.of(spawn, east), "east00", "party6_stage"),
                "east00/party6_stage is the chain door");
        // A relinked script would take the bot through a gate it does not know - refuse.
        Portal relinked = portal(4, "east00", 999999999, "party6_escape", 100, 0);
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(spawn, relinked), "east00", "party6_stage"),
                "a different script is not the declared door");
    }

    /** Boss Rush 970030100: clear / 999999999 / raid_stage (Map9/970030100.img.xml). */
    @Test
    void theBossRushChainDoorIsRaidStage() {
        Portal spawn = portal(0, "sp", 999999999, null, 0, 0);
        Portal clear = portal(4, "clear", 999999999, "raid_stage", 622, 453);

        assertSame(clear, PartyQuestBot.scriptedPortalOf(List.of(spawn, clear), "clear", "raid_stage"),
                "clear/raid_stage is the chain door");
    }

    /** Horntail 240050100: in00 / 999999999 / hontale_Bopen (Map2/240050100.img.xml). */
    @Test
    void theHorntailChainDoorIsHontaleBopen() {
        Portal spawn = portal(0, "sp", 999999999, null, 0, 0);
        Portal st = portal(1, "st00", 999999999, null, 0, 0);
        Portal in = portal(2, "in00", 999999999, "hontale_Bopen", 100, 0);

        assertSame(in, PartyQuestBot.scriptedPortalOf(List.of(spawn, st, in), "in00", "hontale_Bopen"),
                "in00/hontale_Bopen is the chain door");
        // The Magatia maze doors share the in00 name with a DIFFERENT script - the script match
        // is what keeps the Horntail declaration out of Magatia's rooms.
        Portal maze = portal(2, "in00", 999999999, "rnj3_in0", 100, 0);
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(spawn, maze), "in00", "hontale_Bopen"),
                "in00 under another script is not Horntail's door");
    }

    /** Magatia: pt00 (jnr4_rN) and out00 (jnr1_out) are both chain doors, by name only. */
    @Test
    void theMagatiaChainDoorMatchesEitherStageRoomExitName() {
        Portal spawn = portal(0, "sp", 999999999, null, 0, 0);
        Portal pt = portal(1, "pt00", 999999999, "jnr4_r1", 100, 0);
        Portal out = portal(1, "out00", 999999999, "jnr1_out", 100, 0);
        Portal maze = portal(2, "in00", 999999999, "rnj3_in0", 100, 0);

        assertSame(pt, PartyQuestBot.scriptedPortalOf(List.of(spawn, pt), "pt00", null),
                "pt00 is a chain door whatever its stage handler");
        assertSame(out, PartyQuestBot.scriptedPortalOf(List.of(spawn, out), "out00", null),
                "out00 is a chain door whatever its stage handler");
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(spawn, pt), "out00", null),
                "a room whose exit is pt00 reports no out00 door");
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(spawn, maze), "pt00", null),
                "the maze doors are not the chain");
        // A pt00 that has been re-pointed at a real map (the room's own maze exit) is not the
        // script gate - refusing it keeps the bot from warping through someone else's door.
        Portal repointed = portal(1, "pt00", 926110301, "jnr4_r1", 100, 0);
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(spawn, repointed), "pt00", null),
                "a pt00 with a real target is someone else's door");
    }

    /** Null and empty portal lists report no door, like the next00 rule does. */
    @Test
    void aRoomWithoutTheDeclaredDoorReportsNone() {
        assertNull(PartyQuestBot.scriptedPortalOf(null, "next00", null));
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(), "next00", null));
    }
}
