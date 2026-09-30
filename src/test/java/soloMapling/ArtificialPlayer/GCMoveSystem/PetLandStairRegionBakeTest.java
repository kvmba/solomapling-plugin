package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Toy Tower stair drop — the fix's PREMISE, over the REAL WZ geometry. The tower entrance
 * (220000400) climbs from the main deck (y=102) to the tower mouth (y=-258) over six 74px ramps
 * (slope 0.81) with 10-16px treads between them — one walk-CONNECTED chain, but every step RISES
 * 60px, past the pet follower's 40px vertical step threshold. The vertical-chase gate used to key
 * "same walk surface" to SWIM maps only, so on this land staircase an owner one tread up read as
 * "a platform above" and fired the hop branch at a tread the pet could simply WALK to; the hop
 * ploughed into the next tread's underside (a wall hit sheds both velocity components), over a
 * column with NO ground under the stairs — the pet fell off the map and was warped back, every
 * climb.
 *
 * <p>The fix un-gates the engine's nav-REGION test from {@code map.isSwim()}. These pins drive the
 * production bake over the real map and assert what the fix relies on:</p>
 *
 * <ol>
 *   <li>the WHOLE stair chain — deck, every tread, the tower mouth — bakes into ONE nav region,</li>
 *   <li>the region test resolves through the same peek API the follower reads,</li>
 *   <li>genuinely disconnected footings stay in different regions.</li>
 * </ol>
 */
class PetLandStairRegionBakeTest {

    private static final int REAL_MAP_ID = 220000400;
    private static final int BAKE_KEY = 220040400; // re-keyed: never collide with a live bake

    private static MapleMap stairMap;

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        try {
            var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
            setter.setAccessible(true);
            setter.invoke(new ServerManager(), ctx);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        GameConfig.add(cfg("server", "update_interval", "100"));

        stairMap = BotNavigationMapLoader.loadMapGeometry(REAL_MAP_ID);
        // Re-key so this test's bake owns its cache entry (production loads this map under its own
        // id; the geometry bridge keeps this test independent of any live bake order).
        stairMap = reKey(stairMap, BAKE_KEY);
        BotNavigationGraphProvider.getGraph(stairMap, BotMovementProfile.base());
    }

    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static MapleMap reKey(MapleMap src, int newId) {
        MapleMap map = new MapleMap(newId, 0, 0, 920010000, 0.0f);
        map.setFootholds(src.getFootholds());
        java.awt.Rectangle vr = src.getMapArea();
        if (vr != null) {
            map.setMapLineBoundings(vr.y, vr.y + vr.height, vr.x, vr.x + vr.width);
        }
        map.setFieldLimit(src.getFieldLimit());
        return map;
    }

    /** The floor the bidirectional probe reports for a point on the real map. */
    private static Foothold groundAt(int x, int y) {
        Foothold fh = GCMovement.groundFoothold(stairMap, new Point(x, y));
        assertTrue(fh != null, "no ground found at (" + x + "," + y + ") on the real map");
        return fh;
    }

    @Test
    void theWholeStairChainBakesIntoOneRegion() {
        // Real WZ chain: deck y=102 spans x -2256..-90; the stair climbs x -2256 -> -2870 over
        // treads at y=42/-18/-78(=-78? see WZ: y=-18 tread x -2430..-2330)/-138/-198; the tower
        // mouth y=-258 spans x -3633..-2870. Points chosen mid-foothold on each level.
        Foothold deck = groundAt(-1800, 102);    // main deck, right of the stair foot
        Foothold treadLow = groundAt(-2470, -78);   // a mid tread, three steps up the ramp chain
        Foothold mouth = groundAt(-3200, -258);  // the tower-mouth platform

        int deckRegion = GCMovement.peekRegionIdOfFoothold(stairMap, deck);
        int treadRegion = GCMovement.peekRegionIdOfFoothold(stairMap, treadLow);
        int mouthRegion = GCMovement.peekRegionIdOfFoothold(stairMap, mouth);

        assertNotEquals(GCMovement.UNBAKED_REGION, deckRegion, "the real map must have a baked graph");
        assertTrue(deckRegion >= 0, "the main deck must belong to a walk region");
        assertEquals(deckRegion, treadRegion,
                "a mid tread (steps up the ramp chain) is the SAME walk-connected surface");
        assertEquals(deckRegion, mouthRegion,
                "the whole stair chain to the tower mouth is ONE region — the fix's premise");
    }

    @Test
    void theEastDeckStepStaysADifferentRegionFromTheMainDeck() {
        // The y=42 step at x -1473..-1407 sits ABOVE the east deck (y=102) with no ramp between:
        // a genuine 60px wall, a different platform — the region test must NOT merge them.
        Foothold deck = groundAt(-1000, 102);
        Foothold eastStep = groundAt(-1440, 42);
        int deckRegion = GCMovement.peekRegionIdOfFoothold(stairMap, deck);
        int stepRegion = GCMovement.peekRegionIdOfFoothold(stairMap, eastStep);
        assertTrue(deckRegion >= 0 && stepRegion >= 0, "both footings must resolve to regions");
        assertNotEquals(deckRegion, stepRegion,
                "the y=42 east step has no walk connection to the y=102 deck: a different platform");
    }
}
