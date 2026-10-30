package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.Item;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two read-only seams the combat beat's pickup gate is built on.
 *
 * <p>Why a gate exists at all: the looting sweep arms a walk to a pass on the MACRO tick, and the
 * combat beat re-targets the driver at a mob 250ms later. Measured on LPQ stage 1, the macro
 * tick's goal (100,-180) - a pass - became (120,-572) - a mob - on the very next beat call, so
 * the pass was never collected while the bot climbed away from it.
 *
 * <p>Why the gate is derived from the driver's own goal instead of a flag set at issue time: any
 * other producer (walkTo, a park, a follow) replaces that goal and clears the arrival callback the
 * flag would have been cleared by, so a stale flag would silently kill the bot's chase for the
 * rest of the run. These two tests pin the seams that make the stateless version possible.
 */
class LudiPickupGateTest {

    @BeforeAll
    static void bootHost() throws Exception {
        org.springframework.context.ApplicationContext context =
                mock(org.springframework.context.ApplicationContext.class,
                        org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.gms.service.ConfigService config = mock(org.gms.service.ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(org.gms.service.ConfigService.class)).thenReturn(config);
        org.gms.property.ServiceProperty props = mock(org.gms.property.ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(org.gms.property.ServiceProperty.class)).thenReturn(props);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String n : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(n, org.springframework.context.MessageSource.class)).thenReturn(messages);
        }
        var setter = org.gms.manager.ServerManager.class.getDeclaredMethod("setApplicationContext",
                org.springframework.context.ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new org.gms.manager.ServerManager(), context);
        org.gms.dao.entity.GameConfigDO interval = new org.gms.dao.entity.GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        org.gms.config.GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
        BotNavigationGraphProvider.setCacheDir(java.nio.file.Path.of("cache"));
    }

    @Test
    void theDriverGoalSeamReportsWhereTheBotIsWalking() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        Character bot = freshBot(map, new Point(-100, -180));
        install(bot, map);

        GCMovement.move(bot, 100, -180);
        assertEquals(new Point(100, -180), GCMovement.moveTargetOf(bot),
                "the seam must report the goal the driver really holds");

        GCMovement.stop(bot);
        clearTarget(bot);
        assertNull(GCMovement.moveTargetOf(bot), "no goal must read as null, not a stale point");

        states().remove(bot.getId());
        PqActions.clearSeekState(bot.getId());
        MovementClock.reset();
    }

    @Test
    void aDropAtTheDriversGoalIsFoundAndOneElsewhereIsNot() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        Character bot = freshBot(map, new Point(-100, -180));
        install(bot, map);

        inject(map, 998700, realDrop(new Point(100, -180)));
        assertFalse(PqActions.itemsOnFloorAt(bot, new Point(100, -180), new int[]{4001022}).isEmpty(),
                "a pass at the driver's goal is what the beat's gate must recognise");
        assertTrue(PqActions.itemsOnFloorAt(bot, new Point(-50, -180), new int[]{4001022}).isEmpty(),
                "a pass 150px away from the goal must not hold the beat");
        assertTrue(PqActions.itemsOnFloorAt(bot, new Point(100, -180), new int[]{4001156}).isEmpty(),
                "stage 7's arming item is never a pickup target");

        states().remove(bot.getId());
        PqActions.clearSeekState(bot.getId());
        MovementClock.reset();
    }

    private static MapItem realDrop(Point at) {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(1);
        when(owner.getPartyId()).thenReturn(-1);
        MapItem drop = new MapItem(new Item(4001022, (short) 1, (short) 1), at, null, owner, null,
                (byte) 2, false);
        drop.setDropTime(MovementClock.nowMs() - 5_000L);
        return drop;
    }

    private static Character freshBot(MapleMap map, Point at) {
        java.util.concurrent.atomic.AtomicReference<Point> position =
                new java.util.concurrent.atomic.AtomicReference<>(new Point(at));
        Character bot = mock(Character.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(892700);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getJob()).thenReturn(Job.HUNTER);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getPosition()).thenAnswer(i -> position.get());
        when(bot.getParty()).thenReturn(null);
        when(bot.getEventInstance()).thenReturn(null);
        doAnswer(i -> {
            position.set(new Point(i.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        return bot;
    }

    private static void install(Character bot, MapleMap map) throws Exception {
        BotMovementState state = new BotMovementState(bot, null);
        state.movementProfile = BotMovementProfile.base();
        state.lastMap = map;
        state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(state, bot, bot.getPosition());
        states().put(bot.getId(), state);
    }

    private static void clearTarget(Character bot) throws Exception {
        states().get(bot.getId()).moveTarget = null;
    }

    @SuppressWarnings("unchecked")
    private static void inject(MapleMap map, int oid, MapObject object) throws Exception {
        Field objects = MapleMap.class.getDeclaredField("mapobjects");
        objects.setAccessible(true);
        ((Map<Integer, MapObject>) objects.get(map)).put(oid, object);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        Field states = GCMovement.class.getDeclaredField("STATES");
        states.setAccessible(true);
        return (Map<Integer, BotMovementState>) states.get(null);
    }
}
