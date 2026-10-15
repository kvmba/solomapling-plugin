package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotLogic;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The px-vs-sq unit boundary exercised THROUGH the real engine call: a 300px scan sees a
 * drop 200px away and misses one 400px away. The sibling fix's test only asserted the
 * squaring arithmetic and stayed green when the boundary stopped squaring - this drives
 * {@link BotLogic#checkForItemsOnFloor} against a live {@code MapleMap}, so a raw px value
 * handed to the engine (sqrt reach: 300 -> ~17px) turns it red.
 */
class PqLootBoundaryTest {

    private static final int PASS = 4001022;

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
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
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

    @Test
    void aThreeHundredPixelScanReachesTwoHundredAndMissesFourHundred() {
        MapleMap map = new MapleMap(100000001, 0, 0, 100000001, 0.0f);
        map.setMapLineBoundings(-2000, 600, -2000, 2000);

        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(1);

        Point origin = new Point(0, 0);
        map.addMapObject(new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(200, 0), null, owner, null, (byte) 0, false));
        map.addMapObject(new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(400, 0), null, owner, null, (byte) 0, false));

        Character scanner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(scanner.getMap()).thenReturn(map);

        List<MapObject> found = BotLogic.checkForItemsOnFloor(scanner, origin, 300, new int[]{PASS});

        assertEquals(1, found.size(),
                "a 300px scan must see the 200px drop exactly once (got " + found.size()
                        + "); a second hit means the px value reached the engine unsquared as a "
                        + "huge squared bound, zero hits mean px was squared twice");
        assertEquals(new Point(200, 0), found.get(0).getPosition(),
                "the hit is the 200px drop; the 400px one is out of a 300px reach");
    }

    /**
     * A meso drop inside the scan must not crash the sweep. The scan used to walk every hit
     * and read {@code item.getItem().getQuantity()} - meso MapItems carry a null item (the
     * amount lives in the meso field), so any sweep that passed over a mob's meso drop threw
     * an NPE before the id filter could discard it. The dead per-item walk is gone; this
     * keeps it gone and pins that meso is simply not returned as an item hit.
     */
    @Test
    void aMesoDropInRangeDoesNotCrashTheScanAndIsNotAnItemHit() {
        MapleMap map = new MapleMap(100000002, 0, 0, 100000002, 0.0f);
        map.setMapLineBoundings(-2000, 600, -2000, 2000);

        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(1);

        Point origin = new Point(0, 0);
        // 50 meso, 30px away - squarely inside the scan.
        map.addMapObject(new MapItem(50, new Point(30, 0), null, owner, null, (byte) 0, false));

        Character scanner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(scanner.getMap()).thenReturn(map);

        List<MapObject> found = BotLogic.checkForItemsOnFloor(scanner, origin, 300, new int[]{PASS});
        assertTrue(found.isEmpty(),
                "meso is not an item drop; the filter must discard it without touching item fields");
    }

    @Test
    void theBoundaryKeepsTheSquaringInOnePlace() {
        // The arithmetic anchor for the behavioural test above: the engine gets rangePx^2.
        assertEquals(90_000.0, BotLogic.rangeSq(300), 1e-9);
        assertTrue(Math.pow(300, 2) <= BotLogic.rangeSq(300));
    }
}
