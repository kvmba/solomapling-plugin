package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.property.ServiceProperty;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObjectType;
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
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPqData;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningStages;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.BotTypes.Magatia.MagatiaPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQOrchestrator;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisPqData;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.MapVFX.CustomReactor;
import soloMapling.server.BotTickService;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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

    @ParameterizedTest
    @CsvSource({
            "CLERIC,11", "CLERIC,12", "CLERIC,13", "CLERIC,21", "CLERIC,22", "CLERIC,23",
            "CLERIC,31", "CLERIC,32", "CLERIC,33",
            "ASSASSIN,11", "ASSASSIN,12", "ASSASSIN,13", "ASSASSIN,21", "ASSASSIN,22", "ASSASSIN,23",
            "ASSASSIN,31", "ASSASSIN,32", "ASSASSIN,33",
            "FIGHTER,33", "MAGICIAN,33", "THIEF,33"
    })
    void orbisLoungeUsesTheHostPuzzleAndWalksBackDown(String jobName, String combination) {
        int hallId = OrbisPqData.STAGE_LOUNGE;
        assumeTrue(wzAvailable(hallId) && (Files.isRegularFile(Path.of("scripts/portal/party3_r4pt.js"))
                || Files.isRegularFile(Path.of("scripts-zh-CN/portal/party3_r4pt.js"))),
                "GMS083 WZ and portal scripts needed");
        Job job = Job.valueOf(jobName);
        MapleMap hall = realRoom(hallId);
        var instance = mock(EventInstanceManager.class);
        var rooms = new HashMap<Integer, MapleMap>();
        rooms.put(hallId, hall);
        for (int id : OrbisPqData.LOUNGE_ROOMS) rooms.put(id, realRoom(id));
        rooms.forEach((id, map) -> {
            map.setEventInstance(instance);
            when(instance.getMapInstance(id)).thenReturn(map);
        });
        when(instance.getIntProperty(any())).thenAnswer(call -> switch (call.<String>getArgument(0)) {
            case "statusStg0", "statusStg1", "statusStg2", "statusStg3", "statusStg4" -> 1;
            default -> -1;
        });
        when(instance.getProperty("stage4_comb")).thenReturn(combination);

        Point start = GCMovement.portalApproachPoint(hall, hall.getPortal("st00"));
        var pos = new AtomicReference<>(start);
        var here = new AtomicReference<>(hall);
        Character member = spy(new CharacterDouble(BOT_ID, hall, pos::get, pos::set));
        var client = mock(Client.class);
        doReturn(client).when(member).getClient();
        when(client.getPlayer()).thenReturn(member);
        doAnswer(call -> here.get()).when(member).getMap();
        doAnswer(call -> here.get().getId()).when(member).getMapId();
        doReturn(instance).when(member).getEventInstance();
        doReturn(mock(Party.class)).when(member).getParty();
        doReturn(job).when(member).getJob();
        doReturn(60).when(member).getLevel();
        doReturn(true).when(member).isLoggedInWorld();
        when(instance.getLeader()).thenReturn(member);
        when(instance.getPlayers()).thenReturn(List.of(member));

        List<Integer> visited = new ArrayList<>();
        List<String> tried = new ArrayList<>();
        doAnswer(call -> {
            MapleMap destination = call.getArgument(0);
            Portal portal = call.getArgument(1);
            here.set(destination);
            pos.set(new Point(portal.getPosition()));
            if (destination != hall) visited.add(destination.getId());
            return null;
        }).when(member).changeMap(any(MapleMap.class), any(Portal.class));
        doAnswer(call -> {
            Point at = pos.get();
            tried.add(hall.getPortals().stream()
                    .filter(p -> "party3_r4pt".equals(p.getScriptName()))
                    .min(Comparator.comparingDouble(p -> p.getPosition().distanceSq(at)))
                    .orElseThrow().getName());
            pos.set(new Point(hall.getPortal(call.<Integer>getArgument(1)).getPosition()));
            return null;
        }).when(member).changeMap(anyInt(), any());

        Sim sim = registerSim(member, hall, start, pos);
        sim.st().movementProfile = BotMovementProfile.fromCharacter(member);
        var context = new OPQSharedContext();
        var coordinator = mock(OPQOrchestrator.class);
        when(coordinator.getSharedContext()).thenReturn(context);
        try (var generation = mockStatic(BotGeneration.class);
             var runs = mockStatic(OPQOrchestrator.class, call -> coordinator)) {
            OPQBot bot = new OPQBot(member);
            bot.setRunning(true);
            bot.setStateForDebug(OPQBot.OPQBotState.MIDDLE_STAGE);
            boolean upper = job == Job.CLERIC || job == Job.ASSASSIN;
            List<Integer> expectedRooms = upper ? OrbisPqData.LOUNGE_ROOMS : List.of(920010604);
            Point post = hall.getNPCById(OrbisPqData.EAK).getPosition();
            for (int beat = 0; beat < 180; beat++) {
                bot.updateState();
                tickN(sim, 10);
                if (visited.size() == expectedRooms.size() && here.get() == hall
                        && sim.p().distance(post) < 55 && GCMovement.isSettled(member)) break;
            }
            assertEquals(expectedRooms, visited.stream().sorted().toList(),
                    () -> tried + " " + GCMovement.movementStateReport(member));
            assertEquals(hall, here.get());
            assertTrue(sim.p().distance(post) < 55, () -> GCMovement.movementStateReport(member));
            if (upper) {
                var correct = List.of("r4rp1" + combination.charAt(0), "r4rp2" + combination.charAt(1));
                assertTrue(tried.containsAll(correct), tried::toString);
                var failures = tried.stream().filter(guess -> !correct.contains(guess)).toList();
                assertEquals(failures.stream().distinct().toList(), failures);
                if (combination.equals("33")) assertEquals(4, failures.size());
            } else {
                assertTrue(tried.isEmpty(), tried::toString);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"100,100,4444444444444444,01010", "100,100,1234321414232413,10001", "140,123,4321432143214321,01001"})
    void orbisUpwardPassageLearnsTheStairsAndReturnsAfterSettingTheLevers(int speed, int jump, String stairs, String levers) {
        int id = OrbisPqData.STAGE_UP;
        assumeTrue(wzAvailable(id) && hostPortalScript("party3_r6pt"), "GMS083 WZ and scripts needed");
        MapleMap room = realRoom(id);
        var visits = new ArrayList<PortalVisit>();
        var refused = new AtomicBoolean();
        String firstCorrect = "rp01" + stairs.charAt(0);
        Sim sim = scriptSim(room, room, visits,
                visit -> !visit.from().equals(firstCorrect) || !refused.compareAndSet(false, true));
        Character member = sim.st().bot;
        doReturn(speed).when(member).getTotalMoveSpeedStat();
        doReturn(jump).when(member).getTotalJumpStat();
        sim.st().movementProfile = BotMovementProfile.fromCharacter(member);
        var instance = member.getEventInstance();
        when(instance.getIntProperty(any())).thenAnswer(call -> switch (call.<String>getArgument(0)) {
            case "statusStg0", "statusStg1", "statusStg2", "statusStg3", "statusStg4", "statusStg5" -> 1;
            case "statusStg6" -> 0;
            default -> -1;
        });
        when(instance.getProperty("stage6_comb")).thenReturn("0" + stairs);
        when(instance.getProperty("stage6_c")).thenReturn(levers);
        Point start = GCMovement.portalApproachPoint(room, room.getPortal("st00"));
        BotPhysicsEngine.teleportTo(sim.st(), member, start);
        BotMovementManager.resetEntryStateAfterTeleport(sim.st());
        BotNavigationGraphProvider.getGraph(room, sim.st().movementProfile);

        for (var entry : mapData(id).getChildByPath("reactor")) {
            int reactorId = org.gms.provider.DataTool.getIntConvert("id", entry, 0);
            if (reactorId != OrbisPqData.LEVER_REACTOR) continue;
            var lever = new org.gms.server.maps.Reactor(
                    org.gms.server.maps.ReactorFactory.getReactor(reactorId), reactorId);
            lever.setName(org.gms.provider.DataTool.getString("name", entry));
            lever.setPosition(new Point(org.gms.provider.DataTool.getInt("x", entry),
                    org.gms.provider.DataTool.getInt("y", entry)));
            room.addMapObject(lever);
        }
        // A teammate may have already flipped the wrong lever. It must be turned back off.
        room.getReactorByName("3").setState((byte) 1);
        List<String> strikes = new ArrayList<>();
        var coordinator = mock(OPQOrchestrator.class);
        when(coordinator.getSharedContext()).thenReturn(new OPQSharedContext());
        Point eak = room.getNPCById(OrbisPqData.EAK).getPosition();
        try (var generation = mockStatic(BotGeneration.class);
             var runs = mockStatic(OPQOrchestrator.class, call -> coordinator);
             var attack = mockStatic(BotAttack.class);
             var reactor = mockStatic(CustomReactor.class)) {
            attack.when(() -> BotAttack.basicSwing(member)).thenReturn(true);
            reactor.when(() -> CustomReactor.hitReactorWithScript(eq(room), anyInt(), eq(member)))
                    .thenAnswer(call -> {
                        var lever = room.getReactorByOid(call.getArgument(1));
                        strikes.add(lever.getName());
                        lever.setState((byte) (1 - lever.getState()));
                        return null;
                    });
            var bot = new OPQBot(member);
            bot.setRunning(true);
            bot.setStateForDebug(OPQBot.OPQBotState.MIDDLE_STAGE);
            for (int beat = 0; beat < 1_200; beat++) {
                bot.updateState();
                tickN(sim, 15);
                if (strikes.size() == 3 && sim.p().distance(eak) < 55 && GCMovement.isSettled(member)) break;
            }
        }
        List<String> correct = java.util.stream.IntStream.range(0, stairs.length())
                .mapToObj(i -> String.format(java.util.Locale.ROOT, "rp%02d%c", i + 1, stairs.charAt(i))).toList();
        List<String> tried = visits.stream().map(PortalVisit::from).toList();
        assertTrue(refused.get(), "A refused correct portal must still be tried again");
        assertTrue(tried.containsAll(correct), () -> tried + " " + GCMovement.movementStateReport(member));
        List<String> wrong = tried.stream().filter(guess -> !correct.contains(guess)).toList();
        assertEquals(wrong.stream().distinct().toList(), wrong, "Do not repeat a rejected portal");
        assertEquals(3, strikes.size(), () -> strikes + " " + GCMovement.movementStateReport(member));
        for (int i = 0; i < levers.length(); i++) {
            assertEquals(levers.charAt(i) - '0', room.getReactorByName(Integer.toString(i + 1)).getState());
        }
        assertTrue(sim.p().distance(eak) < 55, () -> GCMovement.movementStateReport(member));
    }

    @ParameterizedTest
    @CsvSource({"926100301,rnj5_rp,3333333333", "926110304,jnr5_rp,3021310232"})
    void magatiaUsesItsOwnLaneAndNeverRepeatsARejectedDoor(int mapId, String script, String answer) {
        assumeTrue(wzAvailable(mapId) && hostPortalScript(script), "GMS083 WZ and scripts needed");
        MapleMap lane = realRoom(mapId);
        MapleMap next = realRoom(mapId - mapId % 100 + 100);
        var visits = new ArrayList<PortalVisit>();
        var refused = new AtomicBoolean();
        var firstCorrect = new PortalVisit("pt03", "pt13");
        Sim sim = scriptSim(lane, next, visits, visit -> !visit.equals(firstCorrect)
                || !visits.contains(firstCorrect) || !refused.compareAndSet(false, true));
        Character bot = sim.st().bot;
        when(bot.getEventInstance().getProperty("stage6_comb" + mapId % 10)).thenReturn(answer);
        try (var generation = mockStatic(BotGeneration.class)) {
            var member = new MagatiaPQBot(bot);
            member.setRunning(true);
            for (int beat = 0; beat < 1500 && bot.getMap() == lane; beat++) {
                member.updateState();
                tickN(sim, 10);
            }
        }
        assertEquals(next, bot.getMap(), () -> visits + " " + GCMovement.movementStateReport(bot));
        var rejected = visits.stream().filter(v -> v.to().equals("fail")).map(PortalVisit::from).toList();
        assertEquals(rejected.stream().distinct().toList(), rejected);
        assertEquals(answer.chars().map(c -> c - '0').sum(), rejected.size());
        assertTrue(refused.get(), "A valid, already learned door must be retried after a refused warp");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void amoriaEntersItsOwnGenderDoorAndReturnsAfterThatSideIsClear(int gender) {
        int id = AmoriaPqData.ENTRY_MAP;
        assumeTrue(wzAvailable(id) && hostPortalScript("gendergo"), "GMS083 WZ and scripts needed");
        MapleMap room = realRoom(id);
        List<PortalVisit> visits = new ArrayList<>();
        Sim sim = scriptSim(room, room, visits);
        Character bot = sim.st().bot;
        doReturn(gender).when(bot).getGender();
        Monster mob = mock(Monster.class);
        when(mob.getType()).thenReturn(MapObjectType.MONSTER);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getPosition()).thenReturn(gender == 1 ? new Point(-1440, 1870) : new Point(360, 1450));
        room.addMapObject(mob);
        String prefix = gender == 1 ? "female" : "male";
        try (var generation = mockStatic(BotGeneration.class)) {
            var member = new AmoriaPQBot(bot);
            member.setRunning(true);
            for (int beat = 0; beat < 70 && visits.isEmpty(); beat++) {
                member.updateState();
                tickN(sim, 10);
            }
            assertEquals(List.of(new PortalVisit(prefix + "00", prefix + "01")), visits);
            when(mob.isAlive()).thenReturn(false);
            for (int beat = 0; beat < 50 && sim.p().y > 900; beat++) {
                member.updateState();
                tickN(sim, 10);
            }
            assertTrue(sim.p().y < 900, () -> GCMovement.movementStateReport(bot));
        }
    }

    @Test
    void amoriaOpensAndWalksThroughAllSevenGatesBeforeRallying() {
        int id = 670010600;
        assumeTrue(wzAvailable(id) && hostPortalScript("apqDoor"), "GMS083 WZ and scripts needed");
        MapleMap room = realRoom(id);
        List<PortalVisit> visits = new ArrayList<>();
        Sim sim = scriptSim(room, room, visits);
        Character bot = sim.st().bot;
        var area = mapData(id).getChildByPath("area/2");
        int left = org.gms.provider.DataTool.getInt("x1", area);
        int top = org.gms.provider.DataTool.getInt("y1", area);
        var finish = new Rectangle(left, top, org.gms.provider.DataTool.getInt("x2", area) - left,
                org.gms.provider.DataTool.getInt("y2", area) - top);
        for (var entry : mapData(id).getChildByPath("reactor")) {
            var gate = new org.gms.server.maps.Reactor(
                    org.gms.server.maps.ReactorFactory.getReactor(6702001), 6702001);
            gate.setName(org.gms.provider.DataTool.getString("name", entry));
            gate.setPosition(new Point(org.gms.provider.DataTool.getInt("x", entry),
                    org.gms.provider.DataTool.getInt("y", entry)));
            room.addMapObject(gate);
        }
        var nextBeatMs = new AtomicLong();
        long deadline = MovementClock.nowMs() + 240_000;
        try (var generation = mockStatic(BotGeneration.class);
             var attack = mockStatic(BotAttack.class);
             var reactor = mockStatic(CustomReactor.class);
             var wheel = mockStatic(BotTickService.class)) {
            wheel.when(() -> BotTickService.requestEarlierTick(eq(BOT_ID), any(), anyLong()))
                    .thenAnswer(call -> {
                nextBeatMs.accumulateAndGet(call.<Long>getArgument(2), Math::min);
                return null;
            });
            attack.when(() -> BotAttack.basicSwing(bot)).thenReturn(true);
            reactor.when(() -> CustomReactor.hitReactorWithScript(eq(room), anyInt(), eq(bot)))
                    .thenAnswer(call -> {
                var gate = room.getReactorByOid(call.getArgument(1));
                assertTrue(bot.getPosition().distance(gate.getPosition()) < 75);
                assertTrue(bot.getPosition().x < gate.getPosition().x);
                gate.setState((byte) (gate.getState() + 1));
                return null;
            });
            var member = new AmoriaPQBot(bot);
            member.setRunning(true);
            while (MovementClock.nowMs() < deadline && !finish.contains(sim.p())) {
                nextBeatMs.set(3_500);
                member.updateState();
                tickN(sim, (int) Math.max(1, nextBeatMs.get() / BotPhysicsEngine.cfg.TICK_MS));
            }
        }
        assertTrue(finish.contains(sim.p()), () -> visits + " " + GCMovement.movementStateReport(bot));
        assertTrue(MovementClock.nowMs() < deadline, "The original gate run only allows four minutes");
        assertEquals(java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> new PortalVisit("gt0" + i + "WP", "gt0" + i + "PIB")).toList(), visits);
    }

    private record PortalVisit(String from, String to) {}

    private static boolean hostPortalScript(String name) {
        return Files.isRegularFile(Path.of("scripts/portal", name + ".js"))
                || Files.isRegularFile(Path.of("scripts-zh-CN/portal", name + ".js"));
    }

    /** Play the real script; stub only applying its warp so the virtual movement driver owns position. */
    private static Sim scriptSim(MapleMap initial, MapleMap next, List<PortalVisit> visits) {
        return scriptSim(initial, next, visits, visit -> true);
    }

    private static Sim scriptSim(MapleMap initial, MapleMap next, List<PortalVisit> visits,
                                 Predicate<PortalVisit> allowWarp) {
        var instance = mock(EventInstanceManager.class);
        initial.setEventInstance(instance);
        next.setEventInstance(instance);
        when(instance.getMapInstance(initial.getId())).thenReturn(initial);
        when(instance.getMapInstance(next.getId())).thenReturn(next);
        Point start = initial.getPortal(0).getPosition();
        var pos = new AtomicReference<>(new Point(start));
        var map = new AtomicReference<>(initial);
        Character bot = spy(new CharacterDouble(BOT_ID, initial, pos::get, pos::set));
        var client = mock(Client.class);
        when(client.getPlayer()).thenReturn(bot);
        doReturn(client).when(bot).getClient();
        doReturn(mock(Party.class)).when(bot).getParty();
        doReturn(instance).when(bot).getEventInstance();
        doReturn(Job.FIGHTER).when(bot).getJob();
        doReturn(75).when(bot).getLevel();
        doReturn(true).when(bot).isLoggedInWorld();
        doAnswer(call -> map.get()).when(bot).getMap();
        doAnswer(call -> map.get().getId()).when(bot).getMapId();
        when(instance.getLeader()).thenReturn(bot);
        when(instance.getPlayers()).thenReturn(List.of(bot));
        doAnswer(call -> {
            map.set(call.getArgument(0));
            pos.set(new Point(call.<Portal>getArgument(1).getPosition()));
            return null;
        }).when(bot).changeMap(any(MapleMap.class), any(Portal.class));
        doAnswer(call -> {
            MapleMap destination = instance.getMapInstance(call.getArgument(0));
            Object target = call.getArgument(1);
            Portal arrival = target instanceof Integer n ? destination.getPortal(n)
                    : destination.getPortal((String) target);
            Portal source = map.get().getPortals().stream().filter(p -> p.getScriptName() != null)
                    .min(Comparator.comparingDouble(p -> p.getPosition().distanceSq(pos.get()))).orElseThrow();
            var visit = new PortalVisit(source.getName(), arrival.getName());
            if (!allowWarp.test(visit)) return null;
            visits.add(visit);
            map.set(destination);
            pos.set(new Point(arrival.getPosition()));
            return null;
        }).when(bot).changeMap(anyInt(), any());
        Sim sim = registerSim(bot, initial, start, pos);
        sim.st().movementProfile = BotMovementProfile.fromCharacter(bot);
        return sim;
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
