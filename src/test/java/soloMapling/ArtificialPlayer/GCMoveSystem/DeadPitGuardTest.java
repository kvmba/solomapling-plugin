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
 * The dead-pit guard must hold WITHOUT relying on the WZ forbidFallDown flags: a landing on
 * an unescapable surface (no jump chain out, no rope, no portal) is refused by every
 * non-graph descent path, and a bot already trapped inside gets rescued. This is the
 * plugin-side answer to the LPQ stage-3 pit (922010300: rim y=-160 with the WZ flags on
 * fh243..246, pit floor y=242, 402px deep, walls at x=-265/265, no rope, no portal) - the
 * graph's dead-region prune keeps planned routes out, but the rescue hop and the warmup
 * fallback answer their own "is this landing survivable?" and answered "yes" for any
 * simulated landing, pit floor included.
 *
 * Geometry mirrors that map at test scale: an open rim row, a pit floor 402px below with
 * walls on both ends, and NO forbidFallDown flags anywhere - the guard must read the pit
 * floor as dead purely from the jump physics (apex 77px) and the absence of escapes.
 */
public class DeadPitGuardTest {

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
        GameConfig.add(config("server", "update_interval", "100"));
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

    /**
     * The 922010300 shape without any forbidFallDown: a rim row at y=0 spanning the full
     * base, walls implied by the absence of floor outside x[-265..265], a pit floor at
     * y=402, and nothing between. No ropes, no portals - the rim's only "way down" is the
     * pit, and the pit's only exits would be jumps it cannot make.
     */
    private static MapleMap openRimPit(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        map.setFieldLimit(270972); // MOVEMENTSKILLS forces the BASE profile
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        int id = 1;
        int[][] floors = {
                {0, -265, 265},    // rim row (NO forbidFallDown - the guard must not need it)
                {402, -265, 265},  // pit floor
        };
        for (int[] f : floors) {
            tree.insert(new Foothold(new Point(f[1], f[0]), new Point(f[2], f[0]), id++));
        }
        map.setFootholds(tree);
        return map;
    }

    /** The LPQ stage-2 tower base shape: same pit plus a ladder chain out of its floor. */
    private static MapleMap pitWithRopeEscape(int mapId) {
        MapleMap map = openRimPit(mapId);
        map.addRope(new org.gms.server.maps.Rope(0, 0, 402, false));
        return map;
    }

    @Test
    void aFlaglessPitFloorReadsAsDead() {
        MapleMap map = openRimPit(922011901);
        Foothold pitFloor = map.getFootholds().getAllFootholds().get(1);
        assertFalse(DeadPitGuard.isLivableSurface(map, pitFloor, BotMovementProfile.base()),
                "a 402px basin with no rope and no portal is dead even with no WZ ffd flags");
    }

    @Test
    void theRimAboveReadsAsLivable() {
        MapleMap map = openRimPit(922011902);
        Foothold rim = map.getFootholds().getAllFootholds().get(0);
        // The rim row is ordinary ground (the trap is the pit BELOW it, and the pit has its
        // own verdict): an open row with nothing above it is not a trap.
        assertTrue(DeadPitGuard.isLivableSurface(map, rim, BotMovementProfile.base()),
                "an open rim row is ordinary ground, not a trap");
    }

    @Test
    void aRopeOutOfThePitMakesTheFloorLivable() {
        MapleMap map = pitWithRopeEscape(922011903);
        Foothold pitFloor = map.getFootholds().getAllFootholds().get(1);
        assertTrue(DeadPitGuard.isLivableSurface(map, pitFloor, BotMovementProfile.base()),
                "a rope spanning the pit floor to the rim is an escape (LPQ stage-2 base shape)");
    }

    @Test
    void aPortalOnThePitFloorMakesItLivable() {
        MapleMap map = openRimPit(922011904);
        org.gms.server.maps.Portal exit = mock(org.gms.server.maps.Portal.class, RETURNS_DEEP_STUBS);
        when(exit.getPosition()).thenReturn(new Point(0, 402));
        when(exit.getId()).thenReturn(7);
        map.addPortal(exit);
        Foothold pitFloor = map.getFootholds().getAllFootholds().get(1);
        assertTrue(DeadPitGuard.isLivableSurface(map, pitFloor, BotMovementProfile.base()),
                "a quest portal planted on the floor is the pit's own exit");
    }

    @Test
    void theFallbackRefusesADownJumpTheWzFlagsWouldAllow() {
        MapleMap map = openRimPit(922011905);
        // Standing ON the rim with the target on the pit floor: no WZ ffd flag to stop the
        // down-jump, and the landing probe the fallback consults must read the pit floor as
        // dead so shouldUseDownJump refuses regardless of the drop cap.
        Foothold pitFloor = map.getFootholds().getAllFootholds().get(1);
        assertFalse(DeadPitGuard.isLivableLanding(map, new Point(0, 402), BotMovementProfile.base()),
                "the pit-floor landing point must read dead to the fallback's guard");
    }

    @Test
    void aShallowLedgeAboveAnOpenSkyStaysLivable() {
        // A 150px terrace under an open sky with a stair chain back up: each rise stays
        // inside the 77px base-stat jump apex (150 -> 110 -> 60 -> 0).
        MapleMap map = new MapleMap(922011906, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -200), new Point(265, 600));
        int id = 1;
        int[][] floors = {
                {0, -265, 265},     // top row
                {60, -265, 265},    // step (60px below the top row: one hop)
                {110, -265, 265},   // step (50px below the previous step)
                {150, -100, 100},   // terrace (40px below the last step)
        };
        for (int[] f : floors) {
            tree.insert(new Foothold(new Point(f[1], f[0]), new Point(f[2], f[0]), id++));
        }
        map.setFootholds(tree);
        Foothold terrace = map.getFootholds().getAllFootholds().get(3);
        assertTrue(DeadPitGuard.isLivableSurface(map, terrace, BotMovementProfile.base()),
                "terraces with a stair chain back up are ordinary ground");
    }

    @Test
    void theVerdictCacheKeysOnTheCallerProfile() {
        // The cache bug this pins: one verdict per foothold shared across profiles served a
        // base bot a strong jumper's answer. Geometry: a 120px shelf under an open top row.
        // Base reach = ceil(77) + 25 = 102px -> the shelf is DEAD for a base bot. A max-jump
        // thief (jump 123 -> apex 117 -> reach 142) CAN jump back up -> LIVABLE for it.
        MapleMap map = new MapleMap(922011907, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, 0), new Point(265, 0), 1));    // top row
        tree.insert(new Foothold(new Point(-100, 120), new Point(100, 120), 2)); // the shelf
        map.setFootholds(tree);
        Foothold shelf = map.getFootholds().getAllFootholds().get(1);

        assertFalse(DeadPitGuard.isLivableSurface(map, shelf, BotMovementProfile.base()),
                "a 120px shelf is a trap for a base-stat bot (reach 102px)");
        assertTrue(DeadPitGuard.isLivableSurface(map, shelf, new BotMovementProfile(100, 123)),
                "the SAME shelf is escapable for a max-jump bot (reach 142px)");
    }
}
