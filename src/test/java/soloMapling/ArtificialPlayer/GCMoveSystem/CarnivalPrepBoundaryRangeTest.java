package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 嘉年华准备地图 (980000100) 的左右牵制范围：可站立范围 ∩ VR。
 *
 * <p>报告：bot 从中层平台向右跳，弧线到最右端后被竖直截断，沿边界直落穿过地板行，掉出地图。
 *
 * <p>两层原因，本测试各自钉住：
 * <ol>
 *   <li><b>范围本身用错了</b>：旧实现只取可站立 foothold 范围（-315..495），而这张图的 VR 只到
 *       491。VR 之外的 foothold 段在客户端里真玩家根本走不到，把它当作边界等于允许 bot 站到
 *       一个玩家永远到不了的像素。正确范围是两者交集：-314..491。</li>
 *   <li><b>判定顺序错了</b>：边界钳制发生在碰撞检测之内，一旦 X 越界就直接返回 WALL，把同一步
 *       已经踩到的地板判掉（地板 fh#3 的 progress=1.0 打不过 WALL 的 0.0）。修法是先把这一步的
 *       X 钳进范围，再跑地板/墙/天花板三种检测。</li>
 * </ol>
 */
public class CarnivalPrepBoundaryRangeTest {

    private static final int MAP = 980000100;

    /** 该图可站立 foothold 的原始 X 范围（含 VR 之外那段）。 */
    private static final int RAW_STANDABLE_MIN = -315;
    private static final int RAW_STANDABLE_MAX = 495;

    /** 该图 VR 的 X 范围，也是真玩家的活动边界。 */
    private static final int VR_LEFT = -314;
    private static final int VR_RIGHT = 491;

    /** 地板行（可站立最低一行）的 y。 */
    private static final int FLOOR_Y = 132;

    private static MapleMap map;

    @BeforeAll
    static void boot() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
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
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz 不可用（需要真实 WZ 才能跑这个复现）");
        map = BotNavigationMapLoader.loadMapGeometry(MAP);
    }

    static boolean mapWzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory
                    .getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                    .getData("Map/Map9/980000100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    /** 牵制范围必须是「可站立 ∩ VR」，而不是可站立裸范围。 */
    @Test
    void theClampRangeIsStandableIntersectedWithVr() {
        Point bounds = BotPhysicsEngine.standableXBounds(map);
        org.junit.jupiter.api.Assertions.assertNotNull(bounds);
        org.junit.jupiter.api.Assertions.assertEquals(VR_LEFT, bounds.x,
                "左界应被 VR 收窄到 " + VR_LEFT + "（可站立原始左界 " + RAW_STANDABLE_MIN + "）");
        org.junit.jupiter.api.Assertions.assertEquals(VR_RIGHT, bounds.y,
                "右界应被 VR 收窄到 " + VR_RIGHT + "（可站立原始右界 " + RAW_STANDABLE_MAX + "）");
    }

    /** 越界的一步若踩到地板，必须判 LAND —— 不能因为 X 越界就整步按撞墙处理。 */
    @Test
    void aJumpPastTheBoundaryThatCrossesTheFloorMustLandOnIt() {
        var hit = BotPhysicsEngine.resolveAirCollision(map,
                new Point(VR_RIGHT, 111), new Point(VR_RIGHT + 8, FLOOR_Y + 15));
        org.junit.jupiter.api.Assertions.assertEquals(BotPhysicsEngine.AirCollisionType.LAND, hit.type(),
                "贴边界下落穿过地板行应判 LAND，实际是 " + hit.type());
        org.junit.jupiter.api.Assertions.assertEquals(FLOOR_Y, hit.point().y, "落点必须在地板行上");
        org.junit.jupiter.api.Assertions.assertEquals(VR_RIGHT, hit.point().x,
                "落点必须被钳在牵制右界内，实际 " + hit.point());
    }

    /** 越界但这一步没有任何地面：仍然要被挡在边界上，不能借机飞出去。 */
    @Test
    void aJumpPastTheBoundaryWithNoFloorBelowIsStillHeldOnTheBoundary() {
        var hit = BotPhysicsEngine.resolveAirCollision(map,
                new Point(VR_RIGHT, 75), new Point(VR_RIGHT + 8, 111));
        org.junit.jupiter.api.Assertions.assertEquals(BotPhysicsEngine.AirCollisionType.WALL, hit.type(),
                "空中越界且下方无地面时必须判 WALL，实际 " + hit.type());
        org.junit.jupiter.api.Assertions.assertEquals(VR_RIGHT, hit.point().x, "必须钳在牵制右界上");
    }

    /** 范围内的普通下落不受影响：不能被误判成撞墙。 */
    @Test
    void anOrdinaryFallInsideTheRangeIsUntouched() {
        var hit = BotPhysicsEngine.resolveAirCollision(map, new Point(300, 100), new Point(300, 150));
        org.junit.jupiter.api.Assertions.assertEquals(BotPhysicsEngine.AirCollisionType.LAND, hit.type());
        org.junit.jupiter.api.Assertions.assertEquals(FLOOR_Y, hit.point().y);
        org.junit.jupiter.api.Assertions.assertEquals(300, hit.point().x);
    }

    /**
     * 起点本身已在界外时，落点仍必须钳在范围内。
     *
     * <p>修复过程中真的踩到过这个坑：只钳 nextPos 而不钳 previousPos 时，从 (521,100) 落到
     * (527,160) 会扫到 x=495 那一列的真实地板并落在那儿，等于从另一个方向又破了范围。
     */
    @Test
    void aStepStartingOutsideTheRangeStillLandsInsideIt() {
        int outerX = VR_RIGHT + 30;
        for (int nextX : new int[]{outerX + 6, outerX - 6}) {
            var hit = BotPhysicsEngine.resolveAirCollision(map,
                    new Point(outerX, 100), new Point(nextX, FLOOR_Y + 28));
            boolean inside = hit.point() == null
                    || (hit.point().x >= VR_LEFT && hit.point().x <= VR_RIGHT);
            org.junit.jupiter.api.Assertions.assertTrue(inside,
                    "从界外 " + outerX + " 到 " + nextX + " 的落点越出范围: "
                            + hit.type() + " at " + hit.point());
        }
    }

    /** 界外纯竖直下落且下方无地面：必须挡在边界上，不能放它继续坠。 */
    @Test
    void aStraightFallOutsideTheRangeIsHeldOnTheBound() {
        var hit = BotPhysicsEngine.resolveAirCollision(map,
                new Point(VR_RIGHT + 30, 411), new Point(VR_RIGHT + 30, 447));
        org.junit.jupiter.api.Assertions.assertEquals(BotPhysicsEngine.AirCollisionType.WALL, hit.type());
        org.junit.jupiter.api.Assertions.assertEquals(VR_RIGHT, hit.point().x);
    }
}
