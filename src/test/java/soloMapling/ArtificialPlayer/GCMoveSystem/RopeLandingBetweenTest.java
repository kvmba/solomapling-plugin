package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The UNIT question the rope arrival rule now asks: "does this column have a landing between the
 * target and the rope's bottom?" Pinned directly, so the arrival rule's intent cannot drift.
 *
 * <p>Two real maps bracket the two answers:
 * <ul>
 *   <li>920010300 x=-44: a 3224px ladder whose whole span hangs over the room floor at y=-241.
 *       Every goal on it is a genuine mid-rope hang (no landing between).</li>
 *   <li>926120401 x=5 / x=-50: 34px ropes that cross a platform at y=-417 — a goal above that
 *       platform must NOT be treated as a mid-rope hang, or the bot would freeze in the air one
 *       step short of the ledge it has to climb to.</li>
 * </ul>
 */
public class RopeLandingBetweenTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(any(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    private static GameConfigDO config(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static Rope ropeAt(MapleMap map, int x) {
        for (Rope r : map.getRopes()) {
            if (r.x() == x) {
                return r;
            }
        }
        return null;
    }

    /**
     * 920010300: open floor far below the whole ladder, so no goal on it has a landing between
     * itself and the rope bottom — every one of them is a mid-rope hang.
     */
    @Test
    void openShaftLadderHasNoLandingBetweenAtAnyHeight() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(920010300);
        Rope ladder = ropeAt(map, -44);
        assertNotNull(ladder, "the 3224px ladder at x=-44 on 920010300");
        assertTrue(ladder.bottomY() - ladder.topY() > 3000,
                "this is the long-shaft ladder (len " + (ladder.bottomY() - ladder.topY()) + ")");

        for (int y = ladder.topY() + 40; y < ladder.bottomY() - 40; y += 137) {
            assertFalse(BotPhysicsEngine.hasLandingBetween(map, new Point(-44, y), ladder.bottomY()),
                    "no landing between y=" + y + " and the rope bottom "
                            + ladder.bottomY() + " - a goal there is a real mid-rope hang");
        }
    }

    /** The same ladder's own bottom landing is not "between" - the exclusive bounds. */
    @Test
    void theRopesOwnLandingIsNotBetween() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(920010300);
        Rope ladder = ropeAt(map, -44);
        Point justAboveBottom = new Point(-44, ladder.bottomY() - 1);
        assertFalse(BotPhysicsEngine.hasLandingBetween(map, justAboveBottom, ladder.bottomY()),
                "a platform sharing the rope bottom is the rope's own landing, not a mid-rope one");
    }

    /**
     * The case the ORIGINAL guard was written for, and the one the new rule must not lose: a rope
     * whose own column carries a ledge inside its span. A goal ABOVE that ledge has a real landing
     * between it and the rope bottom, so it is NOT a mid-rope hang — the bot has to keep climbing
     * to the ledge instead of stopping in the air one step short of it.
     *
     * <p>701010310 x=165 spans 336..780 with a ledge at y=349. 82 ropes across the game have this
     * shape.
     */
    @Test
    void aLedgeInsideTheSpanBlocksTheMidRopeHang() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(701010310);
        Rope rope = ropeAt(map, 165);
        assertNotNull(rope, "the rope at x=165 on 701010310");
        Point aboveTheLedge = new Point(165, 340);
        assertTrue(BotPhysicsEngine.hasLandingBetween(map, aboveTheLedge, rope.bottomY()),
                "the ledge at y=349 sits between a goal at y=340 and the rope bottom "
                        + rope.bottomY() + ", so the bot must keep climbing to it");
        // below that ledge there is nothing left before the bottom
        assertFalse(BotPhysicsEngine.hasLandingBetween(map, new Point(165, 400), rope.bottomY()),
                "past the ledge, nothing remains between it and the rope bottom");
    }

    /**
     * A goal that IS a ledge's own pixel must not count that ledge as "a landing further down".
     * The scan starts one pixel past the target, so the ledge the bot is standing on cannot veto
     * its own arrival - without that, arriving exactly onto a platform that happens to sit on this
     * rope's column would refuse to become a hang and leave the goal dangling.
     */
    @Test
    void theGoalsOwnPixelIsNotALandingFurtherDown() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(701010310);
        Rope rope = ropeAt(map, 165);
        Point onTheLedge = new Point(165, 349); // the ledge's exact y on this column
        assertFalse(BotPhysicsEngine.hasLandingBetween(map, onTheLedge, rope.bottomY()),
                "y=349 IS the ledge, not a landing between the goal and the rope bottom");
    }

    /** No footholds at all (a stubbed tree) answers false rather than throwing. */
    @Test
    void aMapWithoutFootholdsAnswersFalse() {
        MapleMap bare = mock(MapleMap.class);
        when(bare.getFootholds()).thenReturn(null);
        assertFalse(BotPhysicsEngine.hasLandingBetween(bare, new Point(0, 0), 100));
    }

    /**
     * 432 of the WZ's 233034 walkable footholds are authored right-to-left (x1 &gt; x2) — e.g. the
     * ledge at x 79..-169 inside 109010108's x=-11 rope (43..245). A span test written as
     * {@code getX1() > x || getX2() < x} misses the column for all of them and reports
     * "no landing", which would turn a real platform into a mid-rope hang and stop the bot one
     * step short of the ledge it has to climb to. 18 rope/foothold pairs have this exact shape.
     */
    @Test
    void aReversedFootholdStillCountsAsALanding() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(109010108);
        Rope rope = ropeAt(map, -11);
        assertNotNull(rope, "the rope at x=-11 on 109010108");
        Foothold covering = null;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isWall() || fh.getX1() <= fh.getX2()) {
                continue;
            }
            if (Math.min(fh.getX1(), fh.getX2()) > -11 || Math.max(fh.getX1(), fh.getX2()) < -11) {
                continue;
            }
            if (fh.getY1() > rope.topY() && fh.getY1() < rope.bottomY()) {
                covering = fh;
                break;
            }
        }
        assertNotNull(covering, "109010108 carries a right-to-left foothold inside the rope's span");
        assertTrue(covering.getX1() > covering.getX2(),
                "this is the reversed-authoring shape under test (x1=" + covering.getX1()
                        + " > x2=" + covering.getX2() + ")");
        assertTrue(BotPhysicsEngine.hasLandingBetween(map, new Point(-11, rope.topY() + 2), rope.bottomY()),
                "the reversed foothold (y=" + covering.getY1()
                        + ") must be seen as a landing between the goal and the rope bottom");
    }
}
