package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A bot must never be able to leave its map's standable x range, and must be pulled back onto it
 * if it somehow gets outside.
 *
 * <p>Reported symptom: a bot ended up past the outermost foothold's x and kept falling, and only
 * a later gate pulled it back.
 *
 * <p>Two defects produced that, both in the horizontal flight bound:
 * <ul>
 *   <li>{@code mapSideBoundaryCollision} bailed out whenever the body was ALREADY outside
 *       ("{@code previousPos.x > boundaryX}"), which did not merely skip one frame — it disarmed
 *       the bound for every later step, in both directions;</li>
 *   <li>{@code findWallCollision} returned early on {@code previousPos.x == nextPos.x}, so a body
 *       falling straight down (airVelX 0, or drag-cancelled) never consulted the bound at all.</li>
 * </ul>
 * Measured on 922010800 with the old code: airborne at x=395 with the standable range ending at
 * 365, the bot fell to y=4971 with no collision ever firing.
 *
 * <p>The bound must be the FOOTHOLD extent, never the VR rect: LPQ's maps have VR ±400 against
 * standable ±365, so a VR test declares a bot in that gap "inside the map".
 */
class MapXBoundClampTest {

    private static final int MAP_ID = 922010800;
    private static MapleMap map;
    private static int standableMinX;
    private static int standableMaxX;

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        try {
            var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
            setter.setAccessible(true);
            setter.invoke(new ServerManager(), ctx);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        org.gms.config.GameConfig.add(cfg("server", "update_interval", "100"));
        map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        standableMinX = Integer.MAX_VALUE;
        standableMaxX = Integer.MIN_VALUE;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.getX1() == fh.getX2()) {
                continue; // wall column: nothing to stand on
            }
            standableMinX = Math.min(standableMinX, Math.min(fh.getX1(), fh.getX2()));
            standableMaxX = Math.max(standableMaxX, Math.max(fh.getX1(), fh.getX2()));
        }
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    /** The reported shape: a body already outside the bound is clamped back on the next step. */
    @Test
    void aBodyOutsideTheBoundIsClampedBackOnTheNextStep() {
        for (int outsideX : new int[]{standableMaxX + 30, standableMinX - 30}) {
            Point from = new Point(outsideX, -300);
            int dir = outsideX > 0 ? 1 : -1;
            var outward = BotPhysicsEngine.resolveAirCollision(map, from, new Point(outsideX + dir * 6, -299));
            assertEquals(BotPhysicsEngine.AirCollisionType.WALL, outward.type(),
                    "an out-of-bounds body moving OUTWARD must be stopped at x=" + outsideX);
            var inward = BotPhysicsEngine.resolveAirCollision(map, from, new Point(outsideX - dir * 6, -299));
            assertEquals(BotPhysicsEngine.AirCollisionType.WALL, inward.type(),
                    "an out-of-bounds body moving INWARD must still be clamped (the old code disarmed here)");
        }
    }

    /**
     * The vertical-fall shape: no horizontal travel at all. The old {@code findWallCollision}
     * early-returned on {@code previousPos.x == nextPos.x}, so this never consulted the bound.
     */
    @Test
    void aPurelyVerticalFallOutsideTheBoundIsStillClamped() {
        for (int outsideX : new int[]{standableMaxX + 30, standableMinX - 30}) {
            Point from = new Point(outsideX, -300);
            Point below = new Point(outsideX, -280); // same x: a straight drop
            var col = BotPhysicsEngine.resolveAirCollision(map, from, below);
            assertEquals(BotPhysicsEngine.AirCollisionType.WALL, col.type(),
                    "a straight drop at x=" + outsideX + " must be clamped, not waved through");
            assertTrue(col.point().x >= standableMinX && col.point().x <= standableMaxX,
                    "the clamp must land inside the standable range, got " + col.point());
        }
    }

    /** A body inside the bound must fly freely: the clamp is not allowed to fire there. */
    @Test
    void aBodyInsideTheBoundIsNotDisturbed() {
        int mid = (standableMinX + standableMaxX) / 2;
        Point from = new Point(mid, -300);
        assertEquals(BotPhysicsEngine.AirCollisionType.NONE,
                BotPhysicsEngine.resolveAirCollision(map, from, new Point(mid + 6, -299)).type(),
                "an ordinary in-bounds step must not be reported as a wall");
        assertEquals(BotPhysicsEngine.AirCollisionType.NONE,
                BotPhysicsEngine.resolveAirCollision(map, from, new Point(mid, -280)).type(),
                "an ordinary in-bounds vertical step must not be reported as a wall");
    }

    /** The bound is foothold-derived; a map with no standable columns answers null so callers fall back. */
    @Test
    void theBoundIsTheFootholdExtent() {
        Point bounds = BotPhysicsEngine.standableXBounds(map);
        assertNotNull(bounds);
        assertEquals(standableMinX, bounds.x, "left bound must be the leftmost standable foothold");
        assertEquals(standableMaxX, bounds.y, "right bound must be the rightmost standable foothold");
        MapleMap empty = mock(MapleMap.class);
        when(empty.getFootholds()).thenReturn(null);
        assertNull(BotPhysicsEngine.standableXBounds(empty), "no standable columns -> null (VR fallback)");
        assertNull(BotPhysicsEngine.standableXBounds(null), "null map -> null");
    }
}
