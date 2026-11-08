package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.server.maps.GenericPortal;
import org.gms.server.maps.Portal;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Locks the rule that a carnival arrival is spread over the map's own spawn doors instead of
 * always being portal 0.
 *
 * <p>The maps in this quest carry several spawn doors ({@code pt=0} with the engine's no-map
 * target): the hall has three, a field lobby five, the holding map nine, and the hosts' own entry
 * uses exactly the same pixel for every member. Warping a party of six onto portal 0 is what made
 * a whole team stand on one spot, so the arrival is now round-robin over those doors.
 *
 * <p>Deliberately not the engine's {@code getRandomPlayerSpawnpoint}: that also accepts {@code pt=1},
 * which on an arena is the teams' own side gates ({@code blue00}, {@code red00}, {@code *_revive}).
 * Landing a bot on one of those puts it on the opposing half of the field.
 */
class CarnivalArrivalDoorTest {

    private static Portal portal(int id, String name, int type, int targetMap, int x) {
        return new Stub(id, name, type, targetMap, new Point(x, 0));
    }

    private static final class Stub extends GenericPortal {
        private final int id;
        private final String name;
        private final int targetMap;
        private final Point pos;

        Stub(int id, String name, int type, int targetMap, Point pos) {
            super(type);
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

    /** The three-door hall: ids 0..2, all {@code sp}. */
    private static List<Portal> hall() {
        return List.of(
                portal(0, "sp", 0, 999999999, -43),
                portal(1, "sp", 0, 999999999, 136),
                portal(2, "sp", 0, 999999999, -218));
    }

    @Test
    void everySpawnDoorIsALegitimateArrival() {
        assertEquals(3, CarnivalPqData.spawnDoors(hall()).size());
    }

    @Test
    void aPartyOfSixDoesNotLandOnOnePixel() {
        List<Portal> doors = CarnivalPqData.spawnDoors(hall());
        assertSame(doors.get(0), CarnivalPqData.doorAt(doors, 0));
        assertSame(doors.get(1), CarnivalPqData.doorAt(doors, 1));
        assertSame(doors.get(2), CarnivalPqData.doorAt(doors, 2));
        assertSame(doors.get(0), CarnivalPqData.doorAt(doors, 3), "round-robin, not a random redraw");
        assertSame(doors.get(1), CarnivalPqData.doorAt(doors, 4));
        assertSame(doors.get(2), CarnivalPqData.doorAt(doors, 5));
    }

    @Test
    void aLargeOrNegativeSlotStillResolvesToOneDoor() {
        List<Portal> doors = CarnivalPqData.spawnDoors(hall());
        // 100_000_001 % 3 == 2, -1 floored into [0,3) == 2: both land on the last door.
        assertSame(doors.get(2), CarnivalPqData.doorAt(doors, 100_000_001));
        assertSame(doors.get(2), CarnivalPqData.doorAt(doors, -1));
    }

    @Test
    void doorsAreOrderedByPortalIdNotByMapIterationOrder() {
        List<Portal> shuffled = List.of(
                portal(2, "sp", 0, 999999999, -218),
                portal(0, "sp", 0, 999999999, -43),
                portal(1, "sp", 0, 999999999, 136));

        List<Portal> doors = CarnivalPqData.spawnDoors(shuffled);
        assertEquals(0, doors.get(0).getId());
        assertEquals(1, doors.get(1).getId());
        assertEquals(2, doors.get(2).getId(),
                "a HashMap-backed portal collection must not make the slot assignment arbitrary");
    }

    @Test
    void theArenasSideDoorsAreNotSpawnDoors() {
        // An arena: sp is the only type-0 door; blue00/red00/*_revive are the sides' own gates.
        List<Portal> arena = List.of(
                portal(0, "sp", 0, 999999999, -2),
                portal(1, "blue00", 1, 999999999, -622),
                portal(2, "red00", 1, 999999999, 641),
                portal(3, "blue_revive", 1, 999999999, 271),
                portal(4, "red_revive", 1, 999999999, -269));

        List<Portal> doors = CarnivalPqData.spawnDoors(arena);
        assertEquals(1, doors.size(), "only sp may be used as an arrival");
        assertSame(arena.get(0), doors.get(0));
    }

    @Test
    void exitsAndStageDoorsAreNotSpawnDoors() {
        // The hall's own data: one sp door plus the st00 stage gate and the out00 exit.
        List<Portal> hall = List.of(
                portal(0, "sp", 0, 999999999, -43),
                portal(3, "st00", 1, 980000100, -426),
                portal(4, "out00", 7, 980000000, -518));
        List<Portal> doors = CarnivalPqData.spawnDoors(hall);
        assertEquals(1, doors.size());
        assertSame(hall.get(0), doors.get(0));
    }

    @Test
    void aMapWithNoSpawnDoorAnswersNullForTheHostToFallBack() {
        assertNull(CarnivalPqData.doorAt(CarnivalPqData.spawnDoors(
                List.of(portal(6, "pt00", 7, 0, 67))), 0));
        assertNull(CarnivalPqData.doorAt(CarnivalPqData.spawnDoors(List.of()), 0));
        assertNull(CarnivalPqData.doorAt(CarnivalPqData.spawnDoors(null), 0));
        assertNull(CarnivalPqData.doorAt(null, 0), "a caller with no map data gets the host fallback");
    }

    @Test
    void nullEntriesInAPartiallyBuiltMapAreSkipped() {
        List<Portal> portals = Arrays.asList(null, portal(0, "sp", 0, 999999999, 0));
        assertEquals(1, CarnivalPqData.spawnDoors(portals).size());
    }
}
