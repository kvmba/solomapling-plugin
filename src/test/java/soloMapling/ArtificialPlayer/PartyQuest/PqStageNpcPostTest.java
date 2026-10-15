package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.life.NPC;
import org.gms.server.life.NPCStats;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the stage-NPC POST contract: the delivery point and the ready ring must be the stage's
 * turn-in NPC, not whichever NPC happens to be nearest.
 *
 * <p>Every Ludi PQ stage room carries TWO NPCs on one ledge: the stage's turn-in NPC
 * (2040036..2040044) and the map-exit NPC 2040047 (Sgt. Anderson), 175-405px apart. The post used
 * to be chosen by proximity among every NPC in the room, so roughly half the rooms posted the
 * party's pass stock - and formed the "ready" ring - at the EXIT while the leader was doing the
 * turn-in at the stage NPC. The quest layer knows its stage, so it now NAMES the NPC.
 *
 * <p>WZ-backed: the NPC ids, the two-NPC rooms and the id ladder are read from the real map data,
 * so the rule cannot silently drift from the shipped maps.
 */
public class PqStageNpcPostTest {

    private static final int EXIT_NPC = 2040047;

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

    private static String mapPath(int mapId) {
        return "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img";
    }

    private static boolean wzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(WZFiles.MAP)
                    .getData(mapPath(922010100)) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The room built from the REAL WZ life entries: every NPC (id + position) on the map. */
    private static MapleMap mapWithWzNpcs(int mapId) {
        Data mapData = org.gms.provider.DataProviderFactory.getDataProvider(WZFiles.MAP)
                .getData(mapPath(mapId));
        MapleMap map = new MapleMap(mapId, 0, 0, 920010000, 0.0f);
        map.setMapLineBoundings(-4000, 600, -3000, 3000);
        Data life = mapData.getChildByPath("life");
        if (life != null) {
            for (Data entry : life) {
                if (!"n".equals(DataTool.getString("type", entry, ""))) {
                    continue;
                }
                int id = DataTool.getIntConvert("id", entry, 0);
                if (id == 0) {
                    continue;
                }
                NPC npc = new NPC(id, new NPCStats("npc" + id));
                npc.setPosition(new Point(DataTool.getInt(entry.getChildByPath("x")),
                        DataTool.getInt(entry.getChildByPath("y"))));
                map.addMapObject(npc);
            }
        }
        return map;
    }

    /** The NPC ids the real WZ room holds. */
    private static List<Integer> wzNpcIds(int mapId) {
        Data mapData = org.gms.provider.DataProviderFactory.getDataProvider(WZFiles.MAP)
                .getData(mapPath(mapId));
        List<Integer> ids = new ArrayList<>();
        Data life = mapData.getChildByPath("life");
        if (life != null) {
            for (Data entry : life) {
                if (!"n".equals(DataTool.getString("type", entry, ""))) {
                    continue;
                }
                int id = DataTool.getIntConvert("id", entry, 0);
                if (id != 0) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static Character botOn(MapleMap map, Point at) {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getId()).thenReturn(9401);
        when(bot.getPosition()).thenReturn(at);
        return bot;
    }

    /**
     * The rule the whole fix rests on: {@code 2040035 + stage} is a real NPC in that stage's room,
     * and the exit NPC is in the same room - i.e. the proximity choice really was ambiguous. Read
     * from the real maps, so the ladder cannot be a guess.
     */
    @Test
    void everyStageNamesItsOwnTurnInNpcAndThatNpcIsInItsRoom() {
        org.junit.jupiter.api.Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        for (int stage = 1; stage <= 9; stage++) {
            int mapId = 922010100 + (stage - 1) * 100;
            int npcId = LudiPqData.stageNpcId(stage);
            assertTrue(npcId > 0, "stage " + stage + " must name a turn-in NPC");
            List<Integer> ids = wzNpcIds(mapId);
            assertTrue(ids.contains(npcId),
                    "stage " + stage + " (" + mapId + ") must hold its turn-in NPC " + npcId + ", got " + ids);
            assertTrue(ids.contains(EXIT_NPC),
                    "the exit NPC must share the room (the choice really was ambiguous), got " + ids);
        }
    }

    /**
     * The behavioural contract: naming the stage NPC resolves the post to THAT NPC, even when the
     * exit NPC is nearer to the bot. The negative half pins the bug's own shape: the unnamed
     * overload must still pick the nearest (the exit, from there), so the two are genuinely
     * different and a silent revert to proximity turns this red.
     */
    @Test
    void namingTheStageNpcWinsOverTheNearerExitNpc() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = mapWithWzNpcs(922010100);
        NPC turnIn = map.getNPCById(LudiPqData.stageNpcId(1));
        NPC exit = map.getNPCById(EXIT_NPC);
        assertNotNull(turnIn, "stage-1 turn-in NPC must be in the room");
        assertNotNull(exit, "stage-1 exit NPC must be in the room");

        // Stand the bot right on the EXIT - the pre-fix shape posts the stock there.
        Character bot = botOn(map, new Point(exit.getPosition().x, exit.getPosition().y));

        Point named = invokeStageNpcSpot(bot, LudiPqData.stageNpcId(1));
        Point nearest = invokeStageNpcSpot(bot, 0);

        assertNotNull(named, "the named post must resolve");
        assertNotNull(nearest, "the nearest-NPC post must resolve");
        assertEquals(exit.getPosition().x, nearest.x,
                "the unnamed post must be the closest NPC (the exit, from here) - the bug's shape");
        assertEquals(turnIn.getPosition().x, named.x,
                "the named post must be the stage turn-in NPC even though the exit is nearer");
        assertTrue(Math.abs(named.x - nearest.x) > 40,
                "the two posts must be materially different spots (" + named + " vs " + nearest
                        + "), or the test proves nothing");
    }

    /**
     * A named stage NPC that is not standing in the room yields NO post: a room the bot should not
     * deliver in (mis-routed, or a future map edit) must keep the stock pocketed rather than
     * silently fall back to some other NPC - that fallback IS the defect.
     */
    @Test
    void anAbsentNamedNpcYieldsNoPostRatherThanAnotherNpc() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = mapWithWzNpcs(922010100);
        assertNull(invokeStageNpcSpot(botOn(map, new Point(0, -180)), 2040999),
                "a named NPC that is not in the room must yield no post (never another NPC)");
    }

    /**
     * A room with a single (non-stage) NPC keeps the plain nearest-NPC behaviour, and a room with
     * no NPC at all yields nothing - the unnamed overload must not start inventing posts.
     */
    @Test
    void roomsWithoutATurnInNpcKeepTheFallbackBehaviour() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        // 922010000 (the exit lobby) holds only the exit NPC.
        MapleMap lobby = mapWithWzNpcs(922010000);
        NPC exit = lobby.getNPCById(EXIT_NPC);
        assertNotNull(exit, "the exit lobby holds the exit NPC");
        Point viaNearest = invokeStageNpcSpot(botOn(lobby, new Point(0, exit.getPosition().y)), 0);
        assertNotNull(viaNearest, "a room with one NPC still resolves a post by proximity");
        assertEquals(exit.getPosition().x, viaNearest.x, "the one NPC is the post");
        // 922010201 (the trap room) holds no NPC at all.
        MapleMap trap = mapWithWzNpcs(922010201);
        assertTrue(wzNpcIds(922010201).isEmpty(), "the trap room really has no NPC in WZ");
        assertNull(invokeStageNpcSpot(botOn(trap, new Point(0, 0)), 0),
                "no NPC in the room -> no post");
    }

    /** The public entry points must carry the stage-NPC seam (a revert drops these overloads). */
    @Test
    void thePublicEntryPointsCarryTheStageNpcSeam() throws Exception {
        Method handoff = PqActions.class.getDeclaredMethod("handItemsToLeaderAfterStage",
                Character.class, int.class, int.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(handoff.getModifiers()));
        Method wait = PqActions.class.getDeclaredMethod("waitNearStageNpc", Character.class, int.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(wait.getModifiers()));
        Method spread = PqActions.class.getDeclaredMethod("spreadNearStageNpc", Character.class, int.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(spread.getModifiers()));
    }

    /** {@code stageNpcSpot(Character, int)} is the private seam the post is built on. */
    private static Point invokeStageNpcSpot(Character bot, int stageNpcId) throws Exception {
        Method m = PqActions.class.getDeclaredMethod("stageNpcSpot", Character.class, int.class);
        m.setAccessible(true);
        return (Point) m.invoke(null, bot, stageNpcId);
    }
}
