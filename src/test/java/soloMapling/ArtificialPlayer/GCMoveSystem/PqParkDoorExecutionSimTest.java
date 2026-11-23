package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPqData;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningStages;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisPqData;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the "bot waits by the door but never comes through" fix, on the REAL WZ
 * geometry and the PRODUCTION driver tick.
 *
 * <p>The macro tick runs the follow beat first and the stage work second. A cleared LPQ stage's
 * stage work is the park beat, and a bot already standing on its own park spot used to have that
 * park beat issue an unconditional {@code GCMovement.stop} - discarding the door order the follow
 * beat had issued moments earlier in the SAME tick. The party then repeated the pair forever:
 * order the door walk, cancel it, order it again.
 *
 * <p>This sim drives exactly that pair over the real 922010100 geometry and then lets the real
 * driver run, so it measures the thing the report is about - does the bot reach the chain door -
 * rather than the shape of the source.
 */
class PqParkDoorExecutionSimTest {

    private static final int MAP_ID = 922010100;
    private static final int BOT_ID = 9461;

    private static Method TICK;

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("messageSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("logSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("exceptionSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();

        TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        TICK.setAccessible(true);

        MovementClock.useVirtual(1_000_000L);
    }

    @AfterAll
    static void resetClock() {
        MovementClock.reset();
    }

    @AfterEach
    void clearMove() throws ReflectiveOperationException {
        var field = GCMovement.class.getDeclaredField("STATES");
        field.setAccessible(true);
        ((java.util.Map<?, ?>) field.get(null)).remove(BOT_ID);
        PqActions.clearSeekState(BOT_ID);
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static org.gms.provider.Data mapData(int id) {
        return org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                .getData("Map/Map" + id / 100_000_000 + "/" + id + ".img");
    }

    private static boolean wzAvailable(int id) {
        try {
            return mapData(id) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos, MapleMap map) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(int mapId, Point start) {
        return sim(realRoom(mapId), start);
    }

    private static Sim sim(MapleMap map, Point start) {
        // Bake the graph up front (production warms it async; the bake itself is covered
        // elsewhere). The driver tick only peeks this cache.
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        AtomicReference<Point> pos = new AtomicReference<>(new Point(start));
        Character bot = new CharacterDouble(BOT_ID, map,
                () -> new Point(pos.get()), p -> pos.set(new Point(p)));
        if (map.getPortals().stream().anyMatch(portal -> portal.getTargetMapId() == map.getId())) {
            // The driver chooses and enters in-map portals; only the host's warp is simulated.
            bot = spy(bot);
            EventInstanceManager instance = mock(EventInstanceManager.class);
            when(instance.getMapInstance(map.getId())).thenReturn(map);
            doReturn(instance).when(bot).getEventInstance();
            doAnswer(call -> {
                Portal portal = call.getArgument(1);
                pos.set(new Point(portal.getPosition()));
                return null;
            }).when(bot).changeMap(eq(map), any(Portal.class));
        }
        return registerSim(bot, map, start, pos);
    }

    private static Sim registerSim(Character bot, MapleMap map, Point start, AtomicReference<Point> pos) {
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        BotPhysicsEngine.teleportTo(st, bot, new Point(start));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        // The production helpers address the engine's own state map; publish this entry there so
        // PqActions.walkTo/GCMovement.stopIfTarget see the same session the driver ticks.
        registerState(bot, st);
        return new Sim(st, pos, map);
    }

    /** Publish the entry into {@code GCMovement.STATES} so the production walk helpers drive it. */
    private static void registerState(Character bot, BotMovementState st) {
        try {
            java.lang.reflect.Field f = GCMovement.class.getDeclaredField("STATES");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Integer, BotMovementState> states =
                    (java.util.Map<Integer, BotMovementState>) f.get(null);
            states.put(bot.getId(), st);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Add the room's NPCs to the navigation loader's real WZ geometry. */
    private static MapleMap realRoom(int mapId) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        org.gms.provider.Data life = mapData(mapId).getChildByPath("life");
        if (life != null) {
            for (org.gms.provider.Data entry : life) {
                if (!"n".equals(org.gms.provider.DataTool.getString("type", entry, ""))) {
                    continue;
                }
                int id = org.gms.provider.DataTool.getIntConvert("id", entry, 0);
                if (id == 0) {
                    continue;
                }
                org.gms.server.life.NPC npc = new org.gms.server.life.NPC(
                        id, new org.gms.server.life.NPCStats("npc" + id));
                npc.setPosition(new Point(
                        org.gms.provider.DataTool.getInt(entry.getChildByPath("x")),
                        org.gms.provider.DataTool.getInt(entry.getChildByPath("y"))));
                map.addMapObject(npc);
            }
        }
        return map;
    }

    private static Point invokeStageNpcSpot(Character bot) {
        try {
            Method m = PqActions.class.getDeclaredMethod("stageNpcSpot", Character.class, int.class);
            m.setAccessible(true);
            return (Point) m.invoke(null, bot, LudiPqData.stageNpcId(1));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code PqActions.parkSpot(Character, Point)} - the per-bot spot the park beat aims at. */
    private static Point parkSpot(Character bot, Point npc) {
        try {
            Method m = PqActions.class.getDeclaredMethod("parkSpot", Character.class, Point.class);
            m.setAccessible(true);
            return (Point) m.invoke(null, bot, npc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("parkSpot unreachable", e);
        }
    }

    private static void tickN(Sim sim, int ticks) {
        tickN(sim, ticks, true);
    }

    private static void tickN(Sim sim, int ticks, boolean observed) {
        for (int i = 0; i < ticks; i++) {
            if (observed && i % 30 == 0) {
                ObserverTracker.markObservedNow(sim.st().bot.getMap());
            }
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
        }
    }

    /**
     * The regression, measured end to end: one macro tick's pair (follow order, then the park
     * beat) must leave the bot walking to the chain door, and the real driver must carry it there.
     */
    @Test
    void oneMacroTickOfFollowThenParkStillReachesTheDoor() {
        assumeTrue(wzAvailable(MAP_ID), "Map.wz needed");
        Sim sim = sim(MAP_ID, new Point(0, 130));
        Point door = sim.map().getPortal("next00").getPosition();
        Character bot = sim.st().bot;

        // A park spot derived from the room's real geometry, the same one the cleared-stage beat
        // walks to. It MUST be the production parkSpot: the bug only fires when the bot stands
        // exactly on its own spot, because that is what sends the park beat down walkTo's
        // "already there" path (otherwise yieldsToLiveWalk stands the park down first).
        // parkSpot is fed the POST (stageNpcSpot), not the raw NPC pixel: that is what production
        // passes, and it is what puts the bot exactly on the spot the park beat will re-arm.
        Point post = invokeStageNpcSpot(bot);
        Point park = parkSpot(bot, post);
        sim.pos().set(new Point(park));
        BotPhysicsEngine.teleportTo(sim.st(), bot, new Point(park));
        BotMovementManager.resetEntryStateAfterTeleport(sim.st());
        assertTrue(Math.abs(park.x - door.x) > 8,
                "fixture: the park spot must be off the door, or the conflict cannot occur");

        // --- the macro tick, in its production order -------------------------------------------
        // 1) follow beat: the door order
        GCMovement.move(bot, door.x, door.y);
        assertNotNull(GCMovement.moveTargetOf(bot), "the follow beat must arm the door walk");
        // 2) stage work: the cleared-stage park beat, same tick, bot already on its park spot
        PqActions.waitNearStageNpc(bot, LudiPqData.stageNpcId(1));
        // ---------------------------------------------------------------------------------------

        assertEquals(door.x, GCMovement.moveTargetOf(bot).x,
                "the park beat discarded the door order issued in the same macro tick - the bot "
                        + "would re-issue it next tick and cancel it again, forever");

        // Now let the real driver run: the order must actually carry the bot to the door.
        tickN(sim, 400);
        double dist = sim.p().distance(door);
        assertTrue(dist <= 40,
                "the bot never reached the chain door: ended at " + sim.p() + ", door " + door
                        + " (dist " + String.format("%.1f", dist) + ")");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void kerningExtraMemberWalksFromTheEntranceToCloto(int stage) {
        int mapId = KerningPqData.STAGE_1 + stage - 1;
        assumeTrue(wzAvailable(mapId), "Map.wz needed");
        MapleMap map = realRoom(mapId);
        Sim sim = sim(map, map.getPortal("sp").getPosition());
        Character bot = sim.st().bot;
        KerningStages.takeMyPlace(bot, stage, 3); // three puzzle spots; this is the fourth bot
        assertArrivesNearNpc(sim, KerningPqData.NPC_CLOTO);
    }

    @ParameterizedTest
    @CsvSource({"103000801,3", "103000802,4", "103000803,5", "922010800,4", "920010500,2"})
    void reachesTheCountedAreaAndWakesTheBotOnArrival(int mapId, int index) {
        assumeTrue(wzAvailable(mapId), "Map.wz needed");
        MapleMap map = realRoom(mapId);
        Sim sim = sim(map, map.getPortal("sp").getPosition());
        int stage = mapId - KerningPqData.STAGE_1 + 1;
        Point spot = switch (mapId) {
            case LudiPqData.STAGE_8 -> LudiPqData.CRATE_SPOTS.get(index);
            case OrbisPqData.STAGE_SEALED -> OrbisPqData.SEALED_AREAS[index];
            default -> KerningPqData.spotsFor(stage).get(index);
        };
        Rectangle area = switch (mapId) {
            case LudiPqData.STAGE_8 -> LudiPqData.crateAreas(map.getAreas()).get(index);
            case OrbisPqData.STAGE_SEALED -> OrbisPqData.SEALED_AREA_RECTS.get(index);
            default -> KerningPqData.areasFor(stage).get(index);
        };
        BotSM fsm = mock(BotSM.class);
        CharacterStorage.addActiveBot(BOT_ID, fsm);
        try {
            PqActions.holdPuzzleSpot(sim.st().bot, spot, area);
            verify(fsm, never()).requestTickWithin(0);
            tickN(sim, 500);
            assertTrue(area.contains(sim.p()), () -> "Outside " + area + " at " + sim.p());
            verify(fsm).requestTickWithin(0);
        } finally {
            CharacterStorage.removeActiveBot(BOT_ID);
        }
    }

    @Test
    void arrivalOutsideTheCountedAreaDoesNotImmediatelyRearmTheTick() {
        int mapId = KerningPqData.STAGE_4;
        assumeTrue(wzAvailable(mapId), "Map.wz needed");
        Point floor = KerningPqData.spotsFor(4).getLast();
        Sim sim = sim(mapId, floor);
        Point spot = new Point(floor.x, floor.y - 4);
        Rectangle area = new Rectangle(spot.x - 1, spot.y - 1, 3, 3);
        BotSM fsm = mock(BotSM.class);
        CharacterStorage.addActiveBot(BOT_ID, fsm);
        try {
            PqActions.holdPuzzleSpot(sim.st().bot, spot, area);
            GCMovementDriver.clearReachedMoveTarget(sim.st());
            assertNull(GCMovement.moveTargetOf(sim.st().bot), "the driver accepts the nearby floor");
            assertFalse(area.contains(sim.p()), "the judge does not count that floor position");
            verify(fsm, never()).requestTickWithin(0);
        } finally {
            CharacterStorage.removeActiveBot(BOT_ID);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void kerningClearedPuzzleReplacesTheSpotWalkButNotTheNextDoor(int stage) {
        int mapId = KerningPqData.STAGE_1 + stage - 1;
        assumeTrue(wzAvailable(mapId), "Map.wz needed");
        var puzzleSpots = KerningPqData.spotsFor(stage);
        Point start = puzzleSpots.getFirst();
        Sim sim = sim(mapId, start);
        Character bot = sim.st().bot;
        if (stage == 2) {
            var rope = sim.map().getRopes().stream().filter(r -> r.x() == start.x).findFirst().orElseThrow();
            BotPhysicsEngine.attachToRope(sim.st(), bot, rope, start.y);
        }
        PqActions.holdPuzzleSpot(bot, puzzleSpots.getLast(), KerningPqData.areasFor(stage).getLast());
        assertEquals(puzzleSpots.getLast(), GCMovement.moveTargetOf(bot));

        PqActions.waitNearPuzzleNpc(bot, KerningPqData.NPC_CLOTO);
        assertArrivesNearNpc(sim, KerningPqData.NPC_CLOTO);

        var door = sim.map().getPortal("next00");
        PqActions.walkToPortal(bot, door);
        Point approach = GCMovement.portalApproachPoint(sim.map(), door);
        assertEquals(approach, GCMovement.moveTargetOf(bot));
        PqActions.waitNearPuzzleNpc(bot, KerningPqData.NPC_CLOTO);
        assertEquals(approach, GCMovement.moveTargetOf(bot), "the leader's door order takes precedence");
    }

    @ParameterizedTest
    @CsvSource({
            "670010300,9201044,540,-1996",
            "670010301,9201044,-629,-1772",
            "670010302,9201044,-45,-1861",
            "670010400,9201044,742,122",
            "670010400,9201044,2030,201",
            "670010400,9201044,1952,48",
            "922010800,2040043,-128,412",
            "922010800,2040043,-216,-220",
            "920010500,2013001,84,-80",
            "920010500,2013001,78,-813"
    })
    void otherPlacementPuzzlesAlsoHaveARealRouteToTheGatheringPost(
            int mapId, int npcId, int x, int y) {
        assumeTrue(wzAvailable(mapId), "Map.wz needed");
        Sim sim = sim(mapId, new Point(x, y));
        if (mapId == 670010400) {
            Point post = AmoriaPqData.STAGE_3_WAIT_POST;
            PqActions.waitAtPuzzlePost(sim.st().bot, post);
            assertArrivesNear(sim, post, () -> PqActions.waitAtPuzzlePost(sim.st().bot, post));
        } else {
            PqActions.waitNearPuzzleNpc(sim.st().bot, npcId);
            assertArrivesNearNpc(sim, npcId);
        }
    }

    @Test
    void kerningCanClimbFromKingSlimeBackToTheRewardNpc() {
        int mapId = KerningPqData.STAGE_5;
        assumeTrue(wzAvailable(mapId), "Map.wz needed");
        Sim sim = sim(mapId, new Point(162, -451));
        Character bot = sim.st().bot;
        Runnable park = () -> PqActions.waitNearStageNpc(bot, KerningPqData.NPC_CLOTO);
        park.run();
        assertArrivesNear(sim, sim.map().getNPCById(KerningPqData.NPC_CLOTO).getPosition(), park, 14);
    }

    private static void assertArrivesNearNpc(Sim sim, int npcId) {
        Point npc = sim.map().getNPCById(npcId).getPosition();
        assertArrivesNear(sim, npc, () -> PqActions.waitNearPuzzleNpc(sim.st().bot, npcId));
    }

    private static void assertArrivesNear(Sim sim, Point post, Runnable park) {
        assertArrivesNear(sim, post, park, 10);
    }

    private static void assertArrivesNear(Sim sim, Point post, Runnable park, int travelBeats) {
        Character bot = sim.st().bot;
        Point target = GCMovement.moveTargetOf(bot);
        assertNotNull(target);
        assertTrue(Math.abs(target.x - post.x) <= 80 && Math.abs(target.y - post.y) <= 30,
                () -> "target " + target + " is not by " + post);
        for (int beat = 0; beat < travelBeats; beat++) {
            tickN(sim, 2_500 / BotPhysicsEngine.cfg.TICK_MS);
            if (sim.p().distance(target) <= 16 && GCMovement.isSettled(bot)) return;
            park.run();
        }
        fail(() -> "target " + target + "; " + GCMovement.movementStateReport(bot));
    }

    @Test
    void kerningStageOneShelfDoesNotTurnAwayFromTheSelectedLaunch() {
        assumeTrue(wzAvailable(KerningPqData.STAGE_1), "Map.wz needed");
        MapleMap map = realRoom(KerningPqData.STAGE_1);
        Sim sim = sim(map, new Point(-293, 102)); // FH49, just before the rounded line rises 1px
        BotMovementState st = sim.st();
        var graph = BotNavigationGraphProvider.getGraph(map);
        int shelf = graph.regionIdByFootholdId.get(49);
        int floor = graph.regionIdByFootholdId.get(38);
        var jumpRight = graph.getOutgoing(shelf).stream()
                .filter(e -> e.type == BotNavigationGraph.EdgeType.JUMP && e.toRegionId == floor
                        && e.launchStepX > 0 && e.containsLaunchX(-280))
                .findFirst().orElseThrow();
        Point goal = GCMovement.portalApproachPoint(map, map.getPortal("next00"));
        GCMovement.move(st.bot, goal.x, goal.y);
        st.navGraph = graph;
        st.navEdge = jumpRight;
        st.navTargetRegionId = floor;
        st.navPathTargetPos = goal;
        st.navJumpLaunchEdge = jumpRight;
        st.navJumpLaunchX = -280; // Pin the launch after the normal in-window variation.
        st.navJumpLaunchDelaySteps = 0;

        for (int i = 0; i < 20 && !st.inAir; i++) {
            tickN(sim, 1);
            assertTrue(st.moveDir >= 0, () -> "turned away from the launch at " + sim.p());
        }
        assertTrue(st.inAir && "exec".equals(st.lastNavDecision), "must execute the chosen jump, not a rescue");
        tickN(sim, 250);
        assertTrue(GCMovement.withinArrivalBox(sim.p(), goal), () -> "did not reach the door: " + sim.p());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void kerningFirstDoorRunsItsScriptFromTheActualLandingSurface(boolean observed) {
        int mapId = 103000800;
        assumeTrue(wzAvailable(mapId), "Map.wz needed");
        Point start = new Point(592, 106);
        MapleMap map = realRoom(mapId);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        var pos = new AtomicReference<>(start);
        Character bot = spy(new CharacterDouble(BOT_ID, map,
                () -> new Point(pos.get()), p -> pos.set(new Point(p))));
        Client client = mock(Client.class);
        doReturn(client).when(bot).getClient();
        Sim sim = registerSim(bot, map, start, pos);
        org.gms.server.maps.Portal door = spy(sim.map().getPortal("next00"));
        doNothing().when(door).enterPortal(client);
        sim.map().addPortal(door);
        Point art = door.getPosition();
        assertEquals(new Point(716, 106), art);
        assertEquals(new Point(716, 115), GCMovement.portalApproachPoint(sim.map(), door));

        assertFalse(PqActions.walkToNpc(bot, KerningPqData.NPC_CLOTO), "standing on Cloto is not the stand-off");
        tickN(sim, 40, observed);
        assertTrue(Math.abs(sim.p().x - start.x) >= 24);
        assertTrue(PqActions.walkToNpc(bot, KerningPqData.NPC_CLOTO));

        PqActions.walkThroughPortal(bot, door);
        verify(door, never()).enterPortal(client);
        tickN(sim, 80, observed);
        assertTrue(GCMovement.withinArrivalBox(sim.p(), GCMovement.portalApproachPoint(sim.map(), door)),
                () -> "bot ended at " + sim.p() + ", move target=" + sim.st().moveTarget
                        + ", coarse=" + sim.st().coarseActive);
        assertTrue(PqActions.movementShielded(BOT_ID), "door owns steering until the script runs");
        PqActions.walkThroughPortal(bot, door);
        verify(door).enterPortal(client);
        assertFalse(PqActions.movementShielded(BOT_ID), "a refused portal returns steering to the stage");
    }
}
