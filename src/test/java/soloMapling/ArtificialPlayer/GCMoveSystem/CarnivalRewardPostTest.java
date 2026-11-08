package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import soloMapling.ArtificialPlayer.BotTypes.Carnival.CarnivalPQBot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks where a Carnival bot stands to read its result - the "the whole team is yanked to the NPC
 * the instant it lands in the reward room" report.
 *
 * <p>The rooms are not laid out like a quest stage. Their exit NPC is a sprite anchor that WZ
 * routinely places off the walkable surface, and on several rooms (CPQ1 fields 4/5, every CPQ2
 * winner room) it sits on a small island 142-192px above a floor with no rope and no upward link.
 * Aiming a walk at that anchor made the movement layer answer in one of three wrong ways, all of
 * which read to an onlooker as the bot teleporting to the NPC: the analytic (unobserved) plan
 * snapped the sprite onto the pixel, the dead-pit guard hauled it onto the only livable surface
 * above (the island), and a mage blinked up.
 *
 * <p>The contract pinned here: the stand is the highest floor on the NPC's own column that this
 * body can reach ON FOOT, and a room whose column offers none yields null - the caller then reads
 * the result where it landed, which is what a player who cannot climb up there does too.
 */
class CarnivalRewardPostTest {

    @TempDir
    static Path navigationCache;
    private static Path originalCacheDir;

    @BeforeAll
    static void bootHost() throws Exception {
        originalCacheDir = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(navigationCache);
        ApplicationContext context = mock(ApplicationContext.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, org.springframework.context.MessageSource.class))
                    .thenReturn(messages);
        }
        Method setter = ServerManager.class.getDeclaredMethod(
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

    @AfterAll
    static void restoreCacheDir() {
        BotNavigationGraphProvider.setCacheDir(originalCacheDir);
    }

    private static MapleMap map(int id) {
        MapleMap map = new MapleMap(id, 0, 0, 0, 0f);
        map.setMapLineBoundings(-4000, 4000, -4000, 4000);
        map.setFootholds(new FootholdTree(new Point(-4000, -4000), new Point(4000, 4000)));
        return map;
    }

    private static void ledge(MapleMap map, int id, int left, int y, int right) {
        map.getFootholds().insert(new Foothold(new Point(left, y), new Point(right, y), id));
    }

    /** A bot standing on {@code stand}, with the map's own graph baked so reachability is real. */
    private static Character bot(MapleMap map, Point stand) {
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        AtomicReference<Point> position = new AtomicReference<>(new Point(stand));
        Character bot = mock(Character.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(880001);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getParty()).thenReturn(null);
        when(bot.getEventInstance()).thenReturn(null);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        when(bot.getClient().getChannel()).thenReturn(1);
        when(bot.getClient().getWorld()).thenReturn(0);
        doAnswer(inv -> {
            position.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        return bot;
    }

    private static Point rewardPost(Character bot, Point npc) throws Exception {
        Method method = CarnivalPQBot.class.getDeclaredMethod(
                "rewardPost", Character.class, Point.class);
        method.setAccessible(true);
        return (Point) method.invoke(null, bot, npc);
    }

    @Test
    void theStandIsTheFloorUnderTheNpcNotTheNpcsOwnPixel() throws Exception {
        // The NPC anchor hovers 13px above the floor it is drawn over - the common case.
        MapleMap map = map(980050001);
        ledge(map, 1, -300, 162, 400);
        Character bot = bot(map, new Point(-300, 162));

        assertEquals(new Point(317, 162), rewardPost(bot, new Point(317, 149)),
                "the goal is the floor the NPC stands over, never its sprite anchor");
    }

    @Test
    void anUnreachableIslandIsSkippedForTheFloorBelow() throws Exception {
        // The CPQ1 field-4/5 shape: the NPC sits on a 138px-wide island 192px above the room's only
        // floor, with no rope and no upward step. Walking to the island is impossible; the floor
        // directly under the NPC is exactly where a player stands to talk across the gap.
        MapleMap map = map(980050002);
        ledge(map, 1, -360, 162, 450);
        ledge(map, 2, 165, -30, 308); // the island - unreachable on foot
        Character bot = bot(map, new Point(-188, 162));

        assertEquals(new Point(227, 162), rewardPost(bot, new Point(227, -32)),
                "a bot must not be sent to an island it cannot walk to");
    }

    @Test
    void aMageDoesNotBlinkUpToTheIslandEither() throws Exception {
        // A mage CAN blink the 192px in two hops, and the honest answer for a chase target would
        // be "reachable". A reward room is not a chase: a player cannot do it, and the bot is only
        // reproducing the wait, so the on-foot question is the one that must be asked.
        MapleMap map = map(980050003);
        ledge(map, 1, -360, 162, 450);
        ledge(map, 2, 165, -30, 308);
        Character bot = bot(map, new Point(-188, 162));
        when(bot.getJob()).thenReturn(Job.IL_WIZARD); // 220, has Teleport

        assertEquals(new Point(227, 162), rewardPost(bot, new Point(227, -32)),
                "reading a result is a walk, not a blink");
    }

    @Test
    void aColumnWithNoWalkableFloorYieldsNoPost() throws Exception {
        // A room whose NPC is only reachable by a blink (the CPQ2 winner rooms' 142px island) has
        // nothing to walk to. Null is the answer the caller wants: read the result in place.
        MapleMap map = map(980050004);
        ledge(map, 1, 60, -209, 109);  // a shelf ABOVE the NPC's row, on another storey
        ledge(map, 2, 165, -30, 308);  // the island the NPC stands on
        Character bot = bot(map, new Point(60, -209));

        assertNull(rewardPost(bot, new Point(16, -218)),
                "no floor on the NPC's column is walkable: read the result where it stands");
    }
}
