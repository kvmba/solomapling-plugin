package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static boolean wzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                    .getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos, MapleMap map) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(Point start) {
        MapleMap map = realRoom();
        // Bake the graph up front (production warms it async; the bake itself is covered
        // elsewhere). The driver tick only peeks this cache.
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        AtomicReference<Point> pos = new AtomicReference<>(new Point(start));
        Character bot = new CharacterDouble(BOT_ID, map,
                () -> new Point(pos.get()), p -> pos.set(new Point(p)));
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
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

    /**
     * The real 922010100 room: WZ footholds, portals and life entries (the turn-in NPC), so both
     * the park spot and the chain door come from shipped data.
     */
    private static MapleMap realRoom() {
        org.gms.provider.Data mapData =
                org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                        .getData("Map/Map9/922010100.img");
        MapleMap map = new MapleMap(MAP_ID, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-4000, 600, -3000, 3000);
        org.gms.server.maps.FootholdTree tree =
                new org.gms.server.maps.FootholdTree(new Point(-4000, -4000), new Point(4000, 4000));
        org.gms.provider.Data footholds = mapData.getChildByPath("foothold");
        for (org.gms.provider.Data footRoot : footholds) {
            for (org.gms.provider.Data footCategory : footRoot) {
                for (org.gms.provider.Data fh : footCategory) {
                    tree.insert(new org.gms.server.maps.Foothold(
                            new Point(org.gms.provider.DataTool.getInt(fh.getChildByPath("x1")),
                                    org.gms.provider.DataTool.getInt(fh.getChildByPath("y1"))),
                            new Point(org.gms.provider.DataTool.getInt(fh.getChildByPath("x2")),
                                    org.gms.provider.DataTool.getInt(fh.getChildByPath("y2"))),
                            Integer.parseInt(fh.getName())));
                }
            }
        }
        map.setFootholds(tree);
        org.gms.server.maps.PortalFactory portalFactory = new org.gms.server.maps.PortalFactory();
        for (org.gms.provider.Data portal : mapData.getChildByPath("portal")) {
            map.addPortal(portalFactory.makePortal(
                    org.gms.provider.DataTool.getInt(portal.getChildByPath("pt")), portal));
        }
        org.gms.provider.Data life = mapData.getChildByPath("life");
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
        for (int i = 0; i < ticks; i++) {
            if (i % 30 == 0) {
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
        assumeTrue(wzAvailable(), "Map.wz needed");
        Sim sim = sim(new Point(0, 130));
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
}
