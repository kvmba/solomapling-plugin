package soloMapling.ArtificialPlayer.BotTypes.Dojo;

import org.gms.server.maps.GenericPortal;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins which of the dojo's two script doors a bot may walk.
 *
 * <p>Every dojo room carries both, and they are not interchangeable for a socketless bot:
 * {@code out00}/{@code dojang_up} only sends {@code DOJO_WARP_UP} and waits for the client to
 * answer - the room change is the client's reply, which a bot never produces, so a bot walking
 * that door stands there for the rest of the run. {@code out001}/{@code dojang_next} does the
 * warp inside the script ({@code pi.warp(mapId + 100)}, or a whole-party warp at a resting
 * floor), so that is the one {@link DojoPQBot} must take.
 *
 * <p>Portals below are transcribed from {@code wz/Map.wz/Map/Map9/925020100.img.xml}, the map
 * every party room ({@code 9250301xx}) links to for its geometry and portals.
 */
class DojoServerDoorTest {

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

    /** The real dojo room portal list: the player's climb door, then the server's warp door. */
    @Test
    void theBotTakesTheDoorTheServerWarpsOn() {
        Portal spawn = portal(0, "sp", 999999999, null, -391, -68);
        Portal climb = portal(7, "out00", 999999999, "dojang_up", 429, 7);
        Portal next = portal(8, "out001", 999999999, "dojang_next", 6, -367);

        Portal chosen = PartyQuestBot.scriptedPortalOf(List.of(spawn, climb, next),
                DojoPqData.SERVER_DOOR, DojoPqData.SERVER_DOOR_SCRIPT);

        assertSame(next, chosen, "dojang_next warps the body itself; dojang_up waits for a client");
    }

    /** The climb door must never be selected, even when it is the only one present. */
    @Test
    void theClimbDoorIsNeverSelected() {
        Portal climb = portal(7, "out00", 999999999, "dojang_up", 429, 7);
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(climb),
                        DojoPqData.SERVER_DOOR, DojoPqData.SERVER_DOOR_SCRIPT),
                "dojang_up cannot move a socketless bot");
    }

    /** A door whose script was relinked is not the door this declaration was written for. */
    @Test
    void aRelinkedDoorIsRefused() {
        Portal relinked = portal(8, "out001", 999999999, "dojang_up", 6, -367);
        assertNull(PartyQuestBot.scriptedPortalOf(List.of(relinked),
                        DojoPqData.SERVER_DOOR, DojoPqData.SERVER_DOOR_SCRIPT),
                "the script name is what makes this door safe to walk");
    }
}
