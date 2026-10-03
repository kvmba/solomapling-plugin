package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REGRESSION for the LPQ stage-1 report: "after killing on a platform the bot follows the mob
 * ABOVE with tiny stutter steps, sways left-right, never climbs up to fight it".
 *
 * <p>Root cause (pinned by a faithful per-beat trace on the real 922010100 geometry): the chase
 * pinned the mob's PIXEL, not its PLATFORM, and its retarget memo then fought the movement layer.
 * Two concrete defects, each pinned here against the REAL {@link PqActions#seekAndAttack} decision
 * logic (no physics — the emergent sim is randomized and would make the assertions flaky):
 *
 * <ol>
 *   <li>a mob above the bot never reads same-level, so {@code tx} stayed at the mob's FIRST-SEEN x
 *       forever — a patrol above was never followed (the "follows but never reaches" part);</li>
 *   <li>after the movement watchdog abandons the (long) climb, the driver holds no move target, but
 *       the seek memo still held the same x and refused to re-arm — the bot sat goal-less on the
 *       rope until the seek's own 4s timeout (the "stutter, gives up, retries" part).</li>
 * </ol>
 */
public class LpqLiveMobChaseRealSeekTest {

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
                any(Object[].class), any(java.util.Locale.class)))
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
        org.junit.jupiter.api.Assumptions.assumeTrue(mapAvailable(), "Map.wz needed");
    }

    static boolean mapAvailable() {
        try {
            var map = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return map.getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    @SuppressWarnings("unchecked")
    private static void registerState(BotMovementState st) throws Exception {
        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        ((Map<Integer, BotMovementState>) f.get(null)).put(st.bot.getId(), st);
    }

    private static void unregisterState(int botId) throws Exception {
        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, BotMovementState> states = (Map<Integer, BotMovementState>) f.get(null);
        states.remove(botId);
    }

    private static Monster liveMob(AtomicReference<Point> mobPos) {
        Monster m = mock(Monster.class, RETURNS_DEEP_STUBS);
        when(m.isAlive()).thenReturn(true);
        when(m.getId()).thenReturn(9300005);
        when(m.getObjectId()).thenReturn(555);
        when(m.getType()).thenReturn(org.gms.server.maps.MapObjectType.MONSTER);
        when(m.getStats().isFriendly()).thenReturn(false);
        when(m.getStats().isBoss()).thenReturn(false);
        when(m.getStats().isUndead()).thenReturn(false);
        when(m.isBoss()).thenReturn(false);
        when(m.getPosition()).thenAnswer(inv -> mobPos.get());
        when(m.getName()).thenReturn("Ratz");
        return m;
    }

    private static Character makeBot(MapleMap map, AtomicReference<Point> pos, int botId) {
        // PqActions seek state is STATIC keyed by bot id: a unique id keeps one test's state
        // from leaking into the next.
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(922010100);
        when(bot.getId()).thenReturn(botId);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        // No equipped weapon: resolveEquippedWeaponType returns null before it touches the
        // DB-backed ItemInformationProvider, so the swing resolves to a no-damage miss and the
        // CHASE path (the thing under test) is what runs.
        when(bot.getInventory(any()).getItem(anyShort())).thenReturn(null);
        org.mockito.Mockito.doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        return bot;
    }

    /** Inject the mock mob into the REAL map's private object table (getAllMonsters is final). */
    @SuppressWarnings("unchecked")
    private static void injectMob(MapleMap map, Monster mob) throws Exception {
        Field f = MapleMap.class.getDeclaredField("mapobjects");
        f.setAccessible(true);
        ((Map<Integer, org.gms.server.maps.MapObject>) f.get(map)).put(mob.getObjectId(), mob);
    }

    private static BotMovementState freshState(MapleMap map, Character bot, int sx, int sy) {
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMapId = 922010100;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(sx, sy));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return st;
    }

    /**
     * Defect 2 (the "stutter, gives up, retries" part): once the movement layer has dropped the
     * move target (its no-progress watchdog abandoned the long climb), the chase must re-arm it even
     * though its own goal x is unchanged. Un-fixed, the retarget memo suppressed the re-issue and the
     * bot sat goal-less until the seek's 4s timeout.
     */
    @Test
    void realSeekReArmsAfterTheDriverDropsTheGoal() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        AtomicReference<Point> pos = new AtomicReference<>(new Point(179, -968));
        AtomicReference<Point> mobPos = new AtomicReference<>(new Point(88, -2581)); // stationary
        Character bot = makeBot(map, pos, 9101);
        BotMovementState st = freshState(map, bot, 179, -968);
        registerState(st);
        Monster mob = liveMob(mobPos);
        injectMob(map, mob);
        try {
            // First beat: the chase arms a move to the mob's platform.
            PqActions.seekAndAttack(bot);
            org.junit.jupiter.api.Assertions.assertTrue((st.moveTarget != null),
                    "the first beat must arm a move toward the mob above");

            // The movement watchdog abandons the (long, no-progress) climb: no goal remains.
            st.moveTarget = null;
            st.moveTargetPrecise = false;
            BotMovementManager.clearNavigationState(st);
            org.junit.jupiter.api.Assertions.assertFalse((st.moveTarget != null),
                    "precondition: the driver holds no goal after the abandon");

            // Next beat: the chase must re-arm the SAME goal (same x) rather than sit goal-less.
            PqActions.seekAndAttack(bot);
            org.junit.jupiter.api.Assertions.assertTrue((st.moveTarget != null),
                    "after the driver drops the goal the chase must re-arm it, not go blind");
            // The re-armed goal is the floor under the mob (the mob stands at y=-2581), i.e. far
            // above the bot's -968 - not the bot's own floor.
            org.junit.jupiter.api.Assertions.assertTrue(st.moveTarget.y < -2000,
                    "the re-armed goal must still aim at the mob's platform row above; got " + st.moveTarget);
        } finally {
            unregisterState(bot.getId());
            PqActions.clearSeekState(bot.getId());
        }
    }

    /**
     * Defect 1 (the "follows but never reaches" part): a mob patrolling its row far ABOVE the bot
     * must be followed by its LIVE x. Un-fixed, the pinned pixel froze at the first-seen x, so the
     * bot walked to a stale column while the mob moved on.
     */
    @Test
    void realSeekFollowsTheLiveXOfAPatrollingMobAbove() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        AtomicReference<Point> pos = new AtomicReference<>(new Point(179, -968));
        AtomicReference<Point> mobPos = new AtomicReference<>(new Point(100, -2581));
        Character bot = makeBot(map, pos, 9102);
        BotMovementState st = freshState(map, bot, 179, -968);
        registerState(st);
        Monster mob = liveMob(mobPos);
        injectMob(map, mob);
        try {
            PqActions.seekAndAttack(bot); // pin the platform at the mob's first x=100
            // The mob patrols along its row (same platform, fh335) to x=-120.
            mobPos.set(new Point(-120, -2581));
            PqActions.seekAndAttack(bot);

            org.junit.jupiter.api.Assertions.assertTrue((st.moveTarget != null),
                    "the chase must hold a goal toward the mob above");
            org.junit.jupiter.api.Assertions.assertEquals(-120, st.moveTarget.x,
                    "the goal must track the mob's LIVE x (-120), not its first-seen pixel (100)");
        } finally {
            unregisterState(bot.getId());
            PqActions.clearSeekState(bot.getId());
        }
    }
}
