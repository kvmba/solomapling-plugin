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
import org.junit.jupiter.api.Assumptions;
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
 * Root-cause regression for the WALK-REGION verdict (8a3df32 follow-up).
 *
 * <p>Escape options belong to the walk-connected surface, not to one foothold: a bot standing on
 * any piece of one walk-linked floor can walk to the piece the door portal sits on and leave. The
 * per-foothold model judged only the probed piece and called the whole 103000900 y=1163 floor row
 * dead (its door portal at x=1214 sits on the row's far piece), after which the guard refused
 * every authorised descent onto the row and the dead-pit self-heal relocated bots standing on it.
 *
 * <p>The verdict is therefore the component's: livable iff ANY walk-connected piece has a local
 * escape. These tests pin the component rule, its one-way direction (an unlinked piece cannot
 * borrow the far piece's portal), the real-WZ regression map, and the genuine traps the sweep
 * must NOT resurrect (the LPQ stage-1 basin).
 */
public class DeadPitWalkRegionVerdictTest {

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
        org.gms.net.server.Server.getInstance();
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

    private static boolean mapWzAvailable() {
        try {
            var s = org.gms.provider.DataProviderFactory.getDataProvider(
                    org.gms.provider.wz.WZFiles.MAP);
            return s.getData("Map/Map1/103000900.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Two flat pieces forming one walk-linked row under a world row beyond the jump apex: the row
     * is a closed basin shape unless an escape is planted on one of its pieces (id 2 = left, 3 = right).
     */
    private static MapleMap linkedRow(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -500, 500);
        FootholdTree tree = new FootholdTree(new Point(-500, -100), new Point(500, 600));
        tree.insert(new Foothold(new Point(-500, -300), new Point(500, -300), 1)); // beyond the apex
        Foothold left = new Foothold(new Point(-400, 0), new Point(0, 0), 2);
        Foothold right = new Foothold(new Point(0, 0), new Point(400, 0), 3);
        left.setNext(3);
        right.setPrev(2);
        tree.insert(left);
        tree.insert(right);
        map.setFootholds(tree);
        return map;
    }

    private static Foothold fh(MapleMap map, int id) {
        for (Foothold f : map.getFootholds().getAllFootholds()) {
            if (f.getId() == id) {
                return f;
            }
        }
        throw new IllegalArgumentException("no foothold " + id);
    }

    private static org.gms.server.maps.Portal portalAt(int id, int x, int y) {
        org.gms.server.maps.Portal portal =
                mock(org.gms.server.maps.Portal.class, RETURNS_DEEP_STUBS);
        when(portal.getPosition()).thenReturn(new Point(x, y));
        when(portal.getId()).thenReturn(id);
        return portal;
    }

    @Test
    void aPortalOnAWalkLinkedPieceMakesTheWholeRowLivable() {
        MapleMap map = linkedRow(922011921);
        map.addPortal(portalAt(7, 300, 0));
        assertTrue(DeadPitGuard.isLivableSurface(map, fh(map, 2), BotMovementProfile.base()),
                "the left piece walks to the right piece's portal - the row is an exit");
        assertTrue(DeadPitGuard.isLivableSurface(map, fh(map, 3), BotMovementProfile.base()),
                "the portal's own piece is livable");
    }

    @Test
    void aLinkedRowWithoutAnyEscapeStaysDead() {
        MapleMap map = linkedRow(922011922);
        assertFalse(DeadPitGuard.isLivableSurface(map, fh(map, 2), BotMovementProfile.base()),
                "no escape anywhere on the row: the component sweep must not mint one");
        assertFalse(DeadPitGuard.isLivableSurface(map, fh(map, 3), BotMovementProfile.base()),
                "no escape anywhere on the row: dead for every member");
    }

    @Test
    void anUnlinkedPieceCannotBorrowTheFarPiecesPortal() {
        MapleMap map = linkedRow(922011923);
        // Unlink: the pieces touch geometrically, but prev/next is the authored walk graph the
        // guard (and the runtime walk) trusts - a piece outside the row must not inherit its exits.
        fh(map, 2).setNext(0);
        fh(map, 3).setPrev(0);
        map.addPortal(portalAt(7, 300, 0));
        assertFalse(DeadPitGuard.isLivableSurface(map, fh(map, 2), BotMovementProfile.base()),
                "an unwalkable piece cannot use the far piece's portal");
        assertTrue(DeadPitGuard.isLivableSurface(map, fh(map, 3), BotMovementProfile.base()),
                "the portal's own piece keeps its local exit");
    }

    @Test
    void theRealDoorRowIsLivableFromEveryPiece() {
        Assumptions.assumeTrue(mapWzAvailable(), "Map.wz needed");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(103000900);
        Foothold leftEnd = fh(map, 642);   // the row's left piece, 674px from the portal
        assertTrue(DeadPitGuard.isLivableSurface(map, leftEnd, BotMovementProfile.base()),
                "the y=1163 floor row walks to the door portal at x=1214: every piece is livable");
    }

    @Test
    void theLpqTowersDeadBasinStaysDead() {
        Assumptions.assumeTrue(mapWzAvailable(), "Map.wz needed");
        MapleMap tower = BotNavigationMapLoader.loadMapGeometry(922010100);
        assertFalse(DeadPitGuard.isLivableSurface(tower, fh(tower, 299), BotMovementProfile.base()),
                "the y=542 basin under the stage-1 rim is still a trap");
        assertTrue(DeadPitGuard.isLivableSurface(tower, fh(tower, 315), BotMovementProfile.base()),
                "the y=130 spawn row (one walk-off to the door row) is ordinary ground");
    }
}
