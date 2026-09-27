package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
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
 * The fallback-engine half of the LPQ stage-1 report: on a tower whose nav graph is still
 * baking, the heuristic fallback only attached to a rope within max(4*walkStep, 90)px — a bot
 * spawned 200-320px from the first ladder steered at the mob floor forever and read as
 * "walks left-right, never climbs". tryImmediateAction now walks the bot toward the nearest
 * VALIDATED grab launch when the selected rope cannot be taken from where it stands.
 *
 * Drives the REAL decision code (tryImmediateAction / selectNearbyRope / nearestGrabLaunchTarget)
 * over the real 922010100 footprint: a Character stub (dynamic engine never ticks it here — the
 * fallback decision layer only reads position/map) at a spread of spawn Xs, target = the nearest
 * mob's floor point, profile = the fieldLimit-forced BASE. Asserts the steer resolves to the
 * ladder column (the grab then fires in range) for EVERY spawn.
 */
public class LpqFallbackRopeApproachTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        // I18nUtil statics resolve these by name at class-init (Job.<clinit> touches them)
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

    private static MapleMap stage1Geometry(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-4000, 600, -265, 265);
        map.setFieldLimit(270972); // the stage's own limit: MOVEMENTSKILLS forces the BASE profile
        FootholdTree tree = new FootholdTree(new Point(-265, -4000), new Point(265, 600));
        int id = 1;
        int[][] floors = {
                {-450, 30, 222}, {-450, 222, 265},
                {-180, -178, 179},
                {130, -265, -185}, {130, -185, -33}, {130, -33, 45}, {130, 45, 265},
                {542, -265, -225}, {542, -225, -135}, {542, -135, -45}, {542, -45, 45},
                {542, 45, 135}, {542, 135, 225}, {542, 225, 265},
        };
        for (int[] f : floors) {
            tree.insert(new Foothold(new Point(f[1], f[0]), new Point(f[2], f[0]), id++));
        }
        // The real WZ links the ground row 4..7 left-to-right (prev/next); the fallback's
        // same-row walkability BFS reads those links, so mirror the real map here.
        java.util.Map<Integer, Foothold> byId = new java.util.HashMap<>();
        for (Foothold fh : tree.getAllFootholds()) {
            byId.put(fh.getId(), fh);
        }
        for (int i = 4; i <= 6; i++) {
            byId.get(i).setNext(i + 1);
        }
        for (int i = 5; i <= 7; i++) {
            byId.get(i).setPrev(i - 1);
        }
        map.setFootholds(tree);
        map.addRope(new Rope(-117, -178, 85, true));
        map.addRope(new Rope(164, -448, -185, true));
        return map;
    }

    private static org.gms.client.Character stubBot(MapleMap map, int x) {
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(x, 130));
        when(bot.getId()).thenReturn(x + 7000);
        return bot;
    }

    @Test
    void everySpawnSteersTowardTheFirstLadderWhileTheGraphBakes() {
        MapleMap map = stage1Geometry(922010198);
        // Nearest mob's floor (the chase target seekAndAttack would aim at)
        Point mobFloor = new Point(88, -450);
        // Spawn spread across the ground floor (portal st00 is x=-9)
        int[] spawns = {-9, 30, 80, 130, 180, 230};

        BotMovementProfile profile = BotMovementProfile.fromCharacter(stubBot(map, -9));
        for (int spawnX : spawns) {
            org.gms.client.Character bot = stubBot(map, spawnX);
            BotMovementState entry = new BotMovementState(bot, null);
            entry.movementProfile = profile;
            entry.graphWarmupFallback = true;
            Point targetPos = new Point(mobFloor);

            boolean acted = BotFallbackMovementManager.tryImmediateAction(entry, bot.getPosition(), targetPos);
            if (acted) {
                // Attached or rope-jumped from the spawn column itself — already climbing.
                assertTrue(entry.climbing || entry.inAir,
                        "spawn " + spawnX + ": immediate action fired but neither attached nor jumped");
                continue;
            }
            Point steer = BotFallbackMovementManager.resolveSteeringTarget(entry, bot.getPosition(), targetPos);
            // The steering target must be on the ladder-1 column (x=-117 +- the grab column) or
            // moving along the validated launch path toward it — never parked at the mob floor.
            assertTrue(Math.abs(steer.x - (-117)) <= 130,
                    "spawn " + spawnX + ": fallback steered to " + steer.x + "," + steer.y
                            + " — not the ladder column; bot would pace the floor forever");
        }
    }

    @Test
    void inRangeSpawnsStillTakeTheRopeDirectly() {
        MapleMap map = stage1Geometry(922010199);
        Point mobFloor = new Point(88, -450);
        // Within reach of ladder 1 (x=-117): the legacy direct-grab path must stay unchanged.
        for (int spawnX : new int[]{-117, -100, -60, -55}) {
            org.gms.client.Character bot = stubBot(map, spawnX);
            BotMovementState entry = new BotMovementState(bot, null);
            entry.movementProfile = BotMovementProfile.base();
            entry.graphWarmupFallback = true;

            boolean acted = BotFallbackMovementManager.tryImmediateAction(entry, bot.getPosition(), new Point(mobFloor));
            assertTrue(acted, "spawn " + spawnX + " is in rope range: the direct grab path must fire");
            assertTrue(entry.climbing || entry.inAir, "spawn " + spawnX + ": no attach and no rope jump");
        }
    }

    @Test
    void flatFloorWithNoRopeIsUntouched() {
        MapleMap map = stage1Geometry(922010197);
        Point sameFloorTarget = new Point(200, 130); // no rope between: plain steering
        for (int spawnX : new int[]{-200, 0}) {
            org.gms.client.Character bot = stubBot(map, spawnX);
            BotMovementState entry = new BotMovementState(bot, null);
            entry.movementProfile = BotMovementProfile.base();
            entry.graphWarmupFallback = true;

            assertFalse(BotFallbackMovementManager.tryImmediateAction(entry, bot.getPosition(), sameFloorTarget),
                    "no rope, no drop: tryImmediateAction must fall through to plain steering");
        }
    }
}
