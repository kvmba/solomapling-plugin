package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * End-to-end proof of the stage-5 floor rule: a hide-less kit chasing a mob that stands on the
 * golems' floor must be refused, and must stay upstairs.
 *
 * <p>Drives the real {@code sneakDoorRooms} entry point, the real combat order and the
 * production driver, so a regression in the veto (or in WHERE it runs) fails here rather than
 * only in the pure helper test.
 */
class LudiStage5FloorRuleExecutionTest {

    private static final int BOT_ID = 890032;
    private Character bot;
    private BotMovementState state;
    private MapleMap map;

    @BeforeAll
    static void bootHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(props);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String n : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(n, org.springframework.context.MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod(
                "setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @BeforeEach
    void setUp() {
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterEach
    void tearDown() throws Exception {
        states().remove(BOT_ID);
        PqActions.clearSeekState(BOT_ID);
        LudiStages.releaseRoomState(BOT_ID);
        MovementClock.reset();
    }

    @Test
    void aGunslingerIsNeverWalkedOntoTheGolemFloor() throws Exception {
        chaseAcrossTheGuardFloor(Job.GUNSLINGER, false);
    }

    @Test
    void aNightWalkerIsNeverWalkedOntoTheGolemFloor() throws Exception {
        chaseAcrossTheGuardFloor(Job.NIGHTWALKER1, false);
    }

    @Test
    void aThiefIsAllowedDown() throws Exception {
        chaseAcrossTheGuardFloor(Job.THIEF, true);
    }

    @Test
    void aBrawlerIsAllowedDown() throws Exception {
        chaseAcrossTheGuardFloor(Job.BRAWLER, true);
    }

    private void chaseAcrossTheGuardFloor(Job job, boolean mayDescend) throws Exception {
        map = realMap(LudiPqData.STAGE_5);
        createBot(new Point(58, -225), job);   // the stage NPC post, upstairs
        inject(monster(9300005, 994201, new Point(-1, 60))); // a killable mob on the golems' floor

        for (int beat = 0; beat < 80; beat++) {
            LudiStages.sneakDoorRooms(bot);      // the production stage-5 macro entry point
            PqActions.seekAndAttack(bot);        // the production combat order
            Point goal = state.moveTarget;
            if (!mayDescend && goal != null && goal.y >= LudiStages.GUARD_FLOOR_Y) {
                fail(job + " was given a goal on the golems' floor: " + goal);
            }
            for (int t = 0; t < 6; t++) {
                tick();
                if (!mayDescend && bot.getPosition().y >= LudiStages.GUARD_FLOOR_Y) {
                    fail(job + " walked down onto the golems' floor at " + bot.getPosition());
                }
            }
        }
        if (!mayDescend) {
            assertTrue(bot.getPosition().y < LudiStages.GUARD_FLOOR_Y,
                    job + " must stay on the upper floors; ended at " + bot.getPosition());
        } else {
            assertTrue(LudiStages.carriesGuardHide(bot), job + " is the hide-kit control");
        }
    }

    private void tick() throws Exception {
        ObserverTracker.markObservedNow(bot.getMapId());
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        try {
            tick.invoke(null, state);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof AssertionError a) throw a;
            throw e;
        }
        MovementClock.advance(50);
    }

    private void createBot(Point initial, Job job) throws Exception {
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        AtomicReference<Point> position = new AtomicReference<>(initial);
        bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        when(bot.getParty()).thenReturn(null);
        when(bot.getEventInstance()).thenReturn(null);
        when(bot.getInventory(any())).thenReturn(mock(org.gms.client.inventory.Inventory.class));
        when(bot.getClient().getChannel()).thenReturn(1);
        when(bot.getClient().getWorld()).thenReturn(0);
        doAnswer(inv -> { position.set(new Point(inv.getArgument(0))); return null; })
                .when(bot).setPosition(any());
        state = new BotMovementState(bot, null);
        state.movementProfile = BotMovementProfile.base();
        state.lastMap = map;
        state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(state, bot, initial);
        states().put(BOT_ID, state);
    }

    private static MapleMap realMap(int id) {
        var source = org.gms.provider.DataProviderFactory.getDataProvider(
                org.gms.provider.wz.WZFiles.MAP);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                source.getData(BotNavigationMapLoader.getMapName(id)) != null,
                "GMS083 Map.wz required");
        return BotNavigationMapLoader.loadMapGeometry(id);
    }

    private static Monster monster(int id, int oid, Point position) {
        Monster mob = mock(Monster.class, RETURNS_DEEP_STUBS);
        when(mob.getId()).thenReturn(id);
        when(mob.getObjectId()).thenReturn(oid);
        when(mob.getType()).thenReturn(MapObjectType.MONSTER);
        when(mob.getPosition()).thenReturn(position);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getName()).thenReturn("floor-rule target");
        return mob;
    }

    @SuppressWarnings("unchecked")
    private void inject(MapObject object) throws Exception {
        Field objects = MapleMap.class.getDeclaredField("mapobjects");
        objects.setAccessible(true);
        ((Map<Integer, MapObject>) objects.get(map)).put(object.getObjectId(), object);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        Field states = GCMovement.class.getDeclaredField("STATES");
        states.setAccessible(true);
        return (Map<Integer, BotMovementState>) states.get(null);
    }
}
