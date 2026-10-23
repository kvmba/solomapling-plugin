package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotTypes.Dojo.DojoPqData;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dojo's geometry makes the "walk to the door" design impossible, and this pins it.
 *
 * <p>The bug this exists for: {@code DojoPQBot} used to reach the server-side door with
 * {@code PqActions.walkTo(bot, door.getPosition()) && PqActions.enterPortal(...)}. That reads as a
 * fix but cannot fire once, because of the map itself. {@code wz/Map.wz/Map/Map9/925020100.img.xml}
 * (the geometry every party room {@code 9250301xx} links to) holds exactly two walkable surfaces:
 *
 * <ul>
 *   <li>the room floor at {@code y = 7} ({@code -499..502}), where the bot spawns and fights;</li>
 *   <li>the door platform at {@code y = -365} ({@code -121..147}), where {@code out001} sits at
 *       {@code (6, -367)}.</li>
 * </ul>
 *
 * <p>There is no rope and no ladder ({@code ladderRope} is empty), and the surfaces differ by
 * 372px while a jump clears {@code 555² / (2 · 2000) ≈ 77px}. The baked nav graph therefore has no
 * edge between them - {@code MovementPlan.inMap(floor → door)} is null - so {@code walkTo} can
 * never report arrival. The server-side placement in {@code DojoPQBot.climb} stands in for the
 * client's {@code DOJO_WARP_UP}, and these numbers are why.
 */
class DojoDoorReachabilityTest {

    private static final int ROOM = 925020100;

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @Test
    void theDoorPlatformIsNotWalkableFromTheRoomFloor() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ROOM);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());

        Point floor = new Point(0, 7);
        Point door = new Point(6, -367);

        int floorRegion = graph.findRegionId(map, floor);
        int doorRegion = graph.findRegionId(map, door);
        assertTrue(floorRegion >= 0 && doorRegion >= 0, "both surfaces must bake as real regions");
        assertNotEquals(floorRegion, doorRegion,
                "the door platform is a separate region - nothing connects the two");
        assertNull(MovementPlan.inMap(graph, map, floor, door),
                "no edge chain reaches the door: walkTo() can never report arrival there");
        assertTrue(map.getRopes().isEmpty(),
                "the room has no rope/ladder - the client's DOJO_WARP_UP is the only lift");
    }

    /** The wall is far taller than a jump, so walking/jumping cannot substitute for the animation. */
    @Test
    void thePlatformIsHigherThanAnyJump() {
        int jumpHeight = (int) (555.0 * 555.0 / (2 * 2000.0)); // jumpSpeed² / (2 · gravityAcc)
        assertTrue(365 - 7 > jumpHeight * 3,
                "372px of wall vs a " + jumpHeight + "px jump - a jump is not a substitute");
    }

    /**
     * Every party room links to that geometry and carries the server-side door on the same
     * platform, so the placement target is data-derived rather than a stage-1 coincidence.
     */
    @Test
    void everyPartyRoomSharesThatDoorGeometry() {
        for (int stage = 1; stage <= DojoPqData.LAST_STAGE; stage++) {
            int mapId = dojoPartyMap(stage);
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);

            Portal door = PartyQuestBot.scriptedPortalOf(map.getPortals(),
                    DojoPqData.SERVER_DOOR, DojoPqData.SERVER_DOOR_SCRIPT);
            assertNotNull(door, mapId + " has no out001/dojang_next door");
            assertTrue(onDoorPlatform(door.getPosition()),
                    mapId + " door is not on the shared top platform: " + door.getPosition());
            assertTrue(map.getRopes().isEmpty(), mapId + " unexpectedly has a rope");
            assertFalse(map.getPortal("out00") != null && DojoPqData.isRestingFloor(mapId),
                    mapId + ": a resting floor unexpectedly has the climb door");
        }
    }

    /**
     * The fighting floors really cannot be walked to: this is the measurement the bot's placement
     * exists to stand in for, on one representative room (all of them share the geometry, pinned
     * above).
     */
    @Test
    void aFightingFloorCannotBeWalkedToItsDoor() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ROOM);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        assertNull(MovementPlan.inMap(graph, map, new Point(-391, -68), new Point(6, -367)),
                "the door became walkable - the placement in DojoPQBot.climb is no longer needed");
    }

    /**
     * The placement the bot actually performs: aiming at the door's own pixel must snap onto the
     * platform under it, inside {@code GCMovement.enterPortal}'s 8px reach. This is what makes
     * {@code climb}'s {@code teleportTo(at.x, at.y)} correct without a hard-coded platform row -
     * a hard-coded row would silently aim at empty air if a room's geometry ever moved.
     */
    @Test
    void thePlacementLandsInsideTheDoorsEntranceReach() {
        for (int stage = 1; stage <= DojoPqData.LAST_STAGE; stage++) {
            int mapId = dojoPartyMap(stage);
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
            Portal door = PartyQuestBot.scriptedPortalOf(map.getPortals(),
                    DojoPqData.SERVER_DOOR, DojoPqData.SERVER_DOOR_SCRIPT);
            assertNotNull(door, mapId + " has no out001/dojang_next door");

            Point at = door.getPosition();
            Point landing = BotPhysicsEngine.findGroundPoint(map, at.x, at.y);
            assertNotNull(landing, mapId + ": nothing to stand on under the door " + at);
            assertTrue(Math.abs(landing.x - at.x) <= 8 && Math.abs(landing.y - at.y) <= 8,
                    mapId + ": the platform under the door (" + landing + ") is outside the "
                            + "engine's 8px portal reach of " + at);
        }
    }

    /**
     * A resting floor has no door reactor and no climb door at all, which is why
     * {@code dojang_next} refuses there ({@code gate == null}) and the bot must reproduce the
     * script's party warp instead of walking {@code out001}.
     */
    @Test
    void aRestingFloorHasNoClimbDoor() {
        for (int stage = 6; stage <= DojoPqData.LAST_STAGE; stage += 6) {
            int mapId = dojoPartyMap(stage);
            assertTrue(DojoPqData.isRestingFloor(mapId), "stage " + stage);
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
            assertNull(map.getPortal("out00"),
                    mapId + ": a resting floor unexpectedly has the climb door");
            // The server door is still in the WZ row - its script is what refuses, not the data.
            assertNotNull(map.getPortal(DojoPqData.SERVER_DOOR), mapId + ": no out001 at all");
        }
    }

    /** The party rooms are a 5-slot ladder of consecutive stage ids (Channel.getDojoSlot). */
    private static int dojoPartyMap(int stage) {
        return org.gms.constants.id.MapId.DOJO_PARTY_BASE + stage * 100;
    }

    /** The door sits on the {@code y = -365} platform, whose only column is {@code -121..147}. */
    private static boolean onDoorPlatform(Point at) {
        return at.y == -367 && at.x >= -121 && at.x <= 147;
    }
}
