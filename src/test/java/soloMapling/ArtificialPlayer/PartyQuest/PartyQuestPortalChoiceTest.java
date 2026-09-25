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
 * Locks how a quest bot picks the door to the room the leader just walked into.
 *
 * <p>This replaced a "walk onto the nearest portal" rule, and the replacement is the whole point:
 * a quest room's <em>nearest</em> portal is normally its spawn point. The real data makes that
 * concrete - Kerning's stage 1 ({@code 103000800}) has three portals, and the one closest to a
 * bot standing on the floor is {@code sp}, whose target is {@code 999999999} (the engine's
 * "no map" sentinel). Walking onto it moves nobody anywhere, so the bot would stand in a room the
 * party had already left while {@code getPlayerCount()} kept counting it - the failure mode that
 * makes a body-count puzzle unsolvable.
 *
 * <p>The portal that matters is the one whose target is the leader's room, and that is what these
 * cases pin. The room layouts are the engine's own WZ data, quoted here so the test fails loudly
 * if the selection rule is ever relaxed back to proximity.
 */
class PartyQuestPortalChoiceTest {

    /** A portal as the engine builds one: id, name, target map, position. */
    private static Portal portal(int id, String name, int targetMap, int x, int y) {
        MapPortalStub p = new MapPortalStub(id, name, targetMap, new Point(x, y));
        return p;
    }

    private static final class MapPortalStub extends GenericPortal {
        private final int id;
        private final String name;
        private final int targetMap;
        private final Point pos;

        MapPortalStub(int id, String name, int targetMap, Point pos) {
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

    /** Kerning PQ stage 1: the nearest portal is the spawn point, and it goes nowhere. */
    @Test
    void picksTheDoorToTheLeadersRoomNotTheNearestPortal() {
        Portal spawn = portal(0, "sp", 999999999, 100, 100);
        Portal stage2 = portal(2, "next00", 103000801, 900, 100);

        Portal chosen = PartyQuestBot.portalTo(List.of(spawn, stage2), 103000801);

        assertSame(stage2, chosen,
                "the door to the leader's room must win over the nearer spawn portal");
    }

    /** A room with no door to the target means "do not move", not "pick something else". */
    @Test
    void returnsNullWhenNoPortalLeadsThere() {
        Portal spawn = portal(0, "sp", 999999999, 0, 0);
        Portal back = portal(1, "st00", 103000000, 50, 0);

        assertNull(PartyQuestBot.portalTo(List.of(spawn, back), 103000801));
    }

    /** A null collection (a bot with no map data yet) is a no-op rather than a crash. */
    @Test
    void nullPortalsYieldNoChoice() {
        assertNull(PartyQuestBot.portalTo(null, 103000801));
    }

    /** The first matching portal wins, so a duplicated target is deterministic. */
    @Test
    void firstMatchingPortalWins() {
        Portal first = portal(3, "next00", 103000801, 10, 10);
        Portal second = portal(4, "next01", 103000801, 20, 20);

        assertSame(first, PartyQuestBot.portalTo(List.of(first, second), 103000801));
    }

    /** Nulls inside the collection (a partially built map) must not throw. */
    @Test
    void nullEntriesAreSkipped() {
        Portal target = portal(2, "next00", 103000801, 900, 100);

        assertSame(target, PartyQuestBot.portalTo(java.util.Arrays.asList(null, target), 103000801));
    }
}
