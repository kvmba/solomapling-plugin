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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins {@link BotPhysicsEngine#findGroundFoothold}'s under-foot answer — the fix for "the bot walks a
 * ledge, decides the platform changed, jumps or drops away and never arrives".
 *
 * <p>Both probes in that method ask "the lowest foothold at or under this y". On a slope the host's
 * {@code FootholdTree.findBelow} TRUNCATES its interpolation while the graph ROUNDS, so a bot drawn on
 * its own foothold can sit one pixel above that foothold's own line; the probe then skips it. Where
 * footholds overlap in one column — every stacked-storey map, and 200000000 is the reported one — the
 * answer lands on a foothold a storey away, the nav layer sees a region change that never happened,
 * and it plans a jump/drop off the ledge.
 *
 * <p>Real geometry (200000000): fh165 spans x -643..-578 at y -50..-48 (a 3% slope), with fh166/167
 * ~11px below and fh208 ~12px above. Walking fh165's own line, the probes answered fh208 at 7 of 14
 * sampled columns — so a bot asked to walk 9px along the ledge instead fell 113px and never arrived.
 */
class StandingFootholdResolutionTest {

    private static final int MAP_ID = 200000000;
    private static final int LEDGE_FH = 165;

    private static MapleMap map;
    private static Foothold ledge;

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms =
                mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(any(String.class), any(Object[].class), any(java.util.Locale.class)))
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
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.getId() == LEDGE_FH) {
                ledge = fh;
            }
        }
        assertNotNull(ledge, "fixture lost foothold " + LEDGE_FH);
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    /** The y the bots actually hold while standing on {@code fh} at {@code x} (the graph's rounding). */
    private static int feetY(Foothold fh, int x) {
        double t = (x - fh.getX1()) / (double) (fh.getX2() - fh.getX1());
        return (int) Math.round(fh.getY1() + (fh.getY2() - fh.getY1()) * t);
    }

    @Test
    void theProbesAloneWouldLoseTheLedge() {
        // Documents the discrepancy the fix compensates for. If this ever stops reproducing, the two
        // interpolations have been aligned underneath and the under-foot answer could be revisited.
        int blind = 0;
        for (int x = ledge.getX1(); x <= ledge.getX2(); x++) {
            Point feet = new Point(x, feetY(ledge, x));
            Foothold probe = BotPhysicsEngine.findBelowIndexed(map, feet);
            if (probe == null || probe.getId() != LEDGE_FH) {
                blind++;
            }
        }
        assertTrue(blind > 0, "the fixture no longer reproduces the interpolation artefact");
    }

    @Test
    void theGroundUnderTheFeetKeepsTheLedgesRegionEverywhere() {
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
        assertNotNull(graph, "fixture lost the baked graph");
        int ownRegion = graph.regionIdByFootholdId.getOrDefault(LEDGE_FH, -1);
        assertTrue(ownRegion >= 0, "the ledge must belong to a region");
        for (int x = ledge.getX1(); x <= ledge.getX2(); x++) {
            Foothold got = BotPhysicsEngine.findGroundFoothold(map, new Point(x, feetY(ledge, x)));
            assertNotNull(got, "no ground resolved at x=" + x);
            int gotRegion = graph.regionIdByFootholdId.getOrDefault(got.getId(), -1);
            assertEquals(ownRegion, gotRegion,
                    "the bot's own ledge region was lost at x=" + x + " (answered fh" + got.getId()
                            + " region " + gotRegion + "; the column stacks fh165/166/167/208)");
        }
    }

    @Test
    void aLedgeOneStoreyBelowOrAboveIsNotSwallowed() {
        // The under-foot answer must not stretch a foothold onto another storey: standing 30px below
        // (or above) the ledge, on the same column, resolves to THAT storey instead.
        int mid = (ledge.getX1() + ledge.getX2()) / 2;
        int below = feetY(ledge, mid) + 30;
        Foothold at = BotPhysicsEngine.findGroundFoothold(map, new Point(mid, below));
        assertNotNull(at, "no ground 30px under the ledge");
        assertFalse(at.getId() == LEDGE_FH, "the ledge was stretched 30px below its own line");
    }

    @Test
    void aPositionBetweenPlatformsFallsBackToTheOrdinaryProbes() {
        // 45px above the ledge's line nothing stands on it, so the under-foot rule must decline and
        // leave the answer to the ordinary probes.
        Point between = new Point((ledge.getX1() + ledge.getX2()) / 2, ledge.getY2() - 45);
        Foothold got = BotPhysicsEngine.findGroundFoothold(map, between);
        assertFalse(got != null && got.getId() == LEDGE_FH,
                "a point 45px above the ledge was attributed to it by the under-foot rule");
    }

    @Test
    void aWallIsNeverGround() {
        Foothold wall = null;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isWall()) {
                wall = fh;
                break;
            }
        }
        assertNotNull(wall, "fixture has no wall");
        Point onWall = new Point(wall.getX1(), wall.getY1());
        Foothold got = BotPhysicsEngine.findGroundFoothold(map, onWall);
        assertFalse(got != null && got.getId() == wall.getId(),
                "a wall column is a vertical stub, never standing ground");
    }

    @Test
    void degenerateInputsDoNotThrow() {
        assertDoesNotThrow(() -> BotPhysicsEngine.findGroundFoothold(map, null));
        assertDoesNotThrow(() -> BotPhysicsEngine.findGroundFoothold(null, new Point(0, 0)));
        assertNull(BotPhysicsEngine.findGroundFoothold(map, null));
        assertDoesNotThrow(() -> BotPhysicsEngine.findGroundFoothold(map, new Point(999999, 999999)));
    }

    @Test
    void everySampledLedgeResolvesToItsOwnRegion() {
        // Generalised guard: walk every sloped, non-wall foothold on this map along its own line and
        // require the ground lookup to answer the same WALK REGION. Foothold identity is too fine -
        // neighbouring segments share an endpoint, so the last pixel of one belongs to both - but a
        // different region is exactly the "the platform changed" signal nav acts on.
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
        assertNotNull(graph, "fixture lost the baked graph");
        int checked = 0;
        int wrong = 0;
        StringBuilder first = new StringBuilder();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isWall() || fh.getY1() == fh.getY2()) {
                continue;
            }
            int lo = Math.min(fh.getX1(), fh.getX2());
            int hi = Math.max(fh.getX1(), fh.getX2());
            if (hi - lo < 8) {
                continue;
            }
            int ownRegion = graph.regionIdByFootholdId.getOrDefault(fh.getId(), -1);
            if (ownRegion < 0) {
                continue;
            }
            for (int x = lo; x <= hi; x += Math.max(1, (hi - lo) / 4)) {
                checked++;
                Foothold got = BotPhysicsEngine.findGroundFoothold(map, new Point(x, feetY(fh, x)));
                int gotRegion = got == null ? -1
                        : graph.regionIdByFootholdId.getOrDefault(got.getId(), -1);
                if (gotRegion != ownRegion) {
                    wrong++;
                    if (first.length() == 0) {
                        first.append("fh").append(fh.getId()).append("(reg").append(ownRegion)
                                .append(") at x=").append(x).append(" -> ")
                                .append(got == null ? "null" : "fh" + got.getId() + "(reg" + gotRegion + ")");
                    }
                }
            }
        }
        assertTrue(checked > 100, "sweep degenerated: only " + checked + " samples");
        assertEquals(0, wrong, wrong + " of " + checked
                + " sloped foothold samples resolved to a different REGION on their own line; first: "
                + first);
    }
}
