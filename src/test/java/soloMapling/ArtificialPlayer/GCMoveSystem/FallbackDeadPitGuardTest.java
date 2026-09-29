package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The bounded-drop rule must hold in the warmup fallback exactly as it does in the baked graph:
 * a straight down-jump whose landing is deeper than DOWN_JUMP_MAX_DROP_PX (200px) lands in a
 * basin a base-stat bot (jump apex 77px) can never leave - the LPQ-tower dead-pit shape
 * (stage 2: rim y=129, pit floor y=542, 413px). shouldUseDownJump must refuse the drop there
 * even though a landing foothold exists, and must still allow a shallow drop onto a mid
 * terrace the bot can jump back up to.
 *
 * Drives the REAL decision code (tryImmediateAction -> shouldUseDownJump) over a synthetic
 * two-terrace map: a rim platform with a 413px pit under it and a 150px-deep mid terrace,
 * target = a point on the pit floor.
 */
public class FallbackDeadPitGuardTest {

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
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO gameConfigDO = new GameConfigDO();
        gameConfigDO.setConfigType(type);
        gameConfigDO.setConfigSubType("0");
        gameConfigDO.setConfigCode(key);
        gameConfigDO.setConfigValue(value);
        gameConfigDO.setConfigClazz("java.lang.Long");
        return gameConfigDO;
    }

    /** Rim at y=0 (x -265..-60 and 20..265) with a mid terrace at y=150 (x -200..-120)
     *  under the left rim piece, and a pit floor at y=413 spanning the whole base. */
    private static MapleMap pitGeometry(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        map.setFieldLimit(270972); // MOVEMENTSKILLS forces the BASE profile
        FootholdTree tree = new FootholdTree(new Point(-265, -500), new Point(265, 600));
        int id = 1;
        int[][] floors = {
                {0, -265, -60},      // left rim
                {0, 20, 265},        // right rim (hole -60..20 between them)
                {150, -200, -120},   // mid terrace under the left rim
                {413, -265, 265},    // pit floor
        };
        for (int[] f : floors) {
            tree.insert(new Foothold(new Point(f[1], f[0]), new Point(f[2], f[0]), id++));
        }
        map.setFootholds(tree);
        return map;
    }

    private static org.gms.client.Character stubBot(MapleMap map, int x, int y) {
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(x, y));
        when(bot.getId()).thenReturn(x + 7100);
        return bot;
    }

    private static BotMovementState entryAt(MapleMap map, int x, int y) {
        org.gms.client.Character bot = stubBot(map, x, y);
        BotMovementState entry = new BotMovementState(bot, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.graphWarmupFallback = true;
        return entry;
    }

    @Test
    void aDeeperThanCapPitIsNeverDownJumped() {
        MapleMap map = pitGeometry(922010191);
        // Standing on the LEFT rim at x=-70: no terrace under this column, so the straight
        // down-jump passes the rim and lands on the pit floor at y=413 (413px deep).
        BotMovementState entry = entryAt(map, -70, 0);
        Point pitFloorTarget = new Point(-70, 413);

        boolean acted = BotFallbackMovementManager.tryImmediateAction(entry, new Point(-70, 0), pitFloorTarget);
        assertFalse(acted, "a 413px drop (bot can only jump 77px back) must never be down-jumped by the fallback");
        assertFalse(entry.downJumpPending, "no crouch may be queued for a dead-pit drop");
    }

    @Test
    void aShallowTerraceDropWithinTheCapIsStillAllowed() {
        MapleMap map = pitGeometry(922010192);
        // Standing on the LEFT rim at x=-160: the terrace (y=150) sits directly below this
        // column, 150px down - within DOWN_JUMP_MAX_DROP_PX, so the graph would bake this
        // edge and the fallback must stay allowed to take it.
        BotMovementState entry = entryAt(map, -160, 0);
        Point terraceTarget = new Point(-160, 150);

        boolean acted = BotFallbackMovementManager.tryImmediateAction(entry, new Point(-160, 0), terraceTarget);
        assertTrue(acted, "a 150px drop onto a terrace must stay allowed (graph caps at 200px)");
        assertTrue(entry.downJumpPending, "the fallback must queue the crouch for a legal drop");
    }

    @Test
    void theCapMatchesTheGraphBuilderConstant() {
        // Parity by construction: one constant, two gates.
        org.junit.jupiter.api.Assertions.assertEquals(200, BotNavigationGraphProvider.DOWN_JUMP_MAX_DROP_PX);
    }
}
