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
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.PortalFactory;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.GCMoveSystem.DriverlessMovementState;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one seam where a stage's PARK beat could cancel the party's FOLLOW order: {@code walkTo}'s
 * "already standing there" shortcut.
 *
 * <p>The quest tick issues more than one walk per tick. {@code PartyQuestBot#tickPartyQuest} runs
 * the follow beat first (to the room's {@code next00}) and the stage work second; on a cleared
 * stage that stage work is the park beat (to the turn-in NPC's post). The two protect against each
 * other with {@code yieldsToLiveWalk}, but that gate only fires while the bot is OFF its park spot
 * - so a bot that is standing exactly on the post sails past it and reaches {@code walkTo}'s
 * shortcut, which used to answer with a blanket {@code GCMovement.stop()}. The driver holds ONE
 * goal, so that stop also discarded the follow order issued moments earlier in the same tick; the
 * next tick re-issued it and the shortcut cancelled it again.
 *
 * <p>What this class pins, in order:
 * <ul>
 *   <li>the scenario really is reachable - the park gate does NOT stand down while the bot is on
 *       its spot with a live door order (otherwise no bug could exist);</li>
 *   <li>the park beat leaves that door order alone (the fix);</li>
 *   <li>the park beat still stands down ITS OWN walk when the bot has arrived on the post;</li>
 *   <li>a puzzle HOLD still pins the bot in place, which is the behaviour the blanket stop used to
 *       provide for stage 8's crate standing.</li>
 * </ul>
 *
 * <p>Driven against the real WZ room (footholds, portals and the stage's turn-in NPC), because the
 * park spot is derived from the map's own ground.
 */
class PqParkKeepOrderTest {

    private static final int MAP_ID = 922010100;
    private static final int BOT_ID = 9451;

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

    @AfterEach
    void clearMovementState() {
        DriverlessMovementState.remove(BOT_ID);
    }

    private static boolean wzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(WZFiles.MAP)
                    .getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The real room: WZ footholds, portals and life entries, no live driver. */
    private static MapleMap realRoom() {
        Data mapData = org.gms.provider.DataProviderFactory.getDataProvider(WZFiles.MAP)
                .getData("Map/Map9/922010100.img");
        MapleMap map = new MapleMap(MAP_ID, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-4000, 600, -3000, 3000);
        map.setFootholds(footholdsOf(mapData));
        PortalFactory portalFactory = new PortalFactory();
        for (Data portal : mapData.getChildByPath("portal")) {
            map.addPortal(portalFactory.makePortal(DataTool.getInt(portal.getChildByPath("pt")), portal));
        }
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

    private static FootholdTree footholdsOf(Data mapData) {
        FootholdTree tree = new FootholdTree(new Point(-4000, -4000), new Point(4000, 4000));
        Data footholdData = mapData.getChildByPath("foothold");
        if (footholdData == null) {
            return tree;
        }
        for (Data footRoot : footholdData) {
            for (Data footCategory : footRoot) {
                for (Data fh : footCategory) {
                    tree.insert(new Foothold(
                            new Point(DataTool.getInt(fh.getChildByPath("x1")),
                                    DataTool.getInt(fh.getChildByPath("y1"))),
                            new Point(DataTool.getInt(fh.getChildByPath("x2")),
                                    DataTool.getInt(fh.getChildByPath("y2"))),
                            Integer.parseInt(fh.getName())));
                }
            }
        }
        return tree;
    }

    private static Character botOn(MapleMap map, Point at) {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getPosition()).thenReturn(at);
        return bot;
    }

    /** {@code PqActions.stageNpcSpot(Character, int)} - the post the park beat aims at. */
    private static Point post(Character bot) {
        return invoke(bot, "stageNpcSpot", int.class, LudiPqData.stageNpcId(1));
    }

    /** {@code PqActions.parkSpot(Character, Point)} - the per-bot spot derived from the post. */
    private static Point parkSpot(Character bot, Point npc) {
        return invoke(bot, "parkSpot", Point.class, npc);
    }

    private static Point invoke(Character bot, String name, Class<?> argType, Object arg) {
        try {
            Method m = PqActions.class.getDeclaredMethod(name, Character.class, argType);
            m.setAccessible(true);
            return (Point) m.invoke(null, bot, arg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name + " unreachable", e);
        }
    }

    /**
     * The precondition the fix rests on: standing on its own post with a live door order, the park
     * beat is NOT stood down by {@code yieldsToLiveWalk} (that gate only fires once the bot has
     * moved off the spot). If this ever becomes true, the bug becomes unreachable and the rest of
     * this class would be testing nothing - so it is asserted, not assumed.
     */
    @Test
    void aParkedBotWithALiveDoorOrderIsNotStoodDownByTheYieldGate() {
        assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = realRoom();
        Character probe = botOn(map, new Point(0, 0));
        Point post = post(probe);
        Point park = parkSpot(probe, post);

        Character bot = botOn(map, park);
        DriverlessMovementState.inject(bot);
        GCMovement.move(bot, map.getPortal("next00").getPosition().x,
                map.getPortal("next00").getPosition().y);

        assertTrue(GCMovement.hasMoveTarget(bot), "the door order must be live");
        assertFalse(PqActions.standsDownForLiveWalk(
                        GCMovement.hasMoveTarget(bot), bot.getPosition(), park),
                "on its own post the park gate must NOT stand down - this is the seam the bug "
                        + "lives in; if it started standing down, the fix would be moot");
    }

    /**
     * The regression: the park beat that runs after the follow beat, in the same macro tick, must
     * leave the follow order alone.
     */
    @Test
    void theParkBeatMustNotCancelTheFollowOrder() {
        assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = realRoom();
        Character probe = botOn(map, new Point(0, 0));
        Point post = post(probe);
        Point park = parkSpot(probe, post);
        var door = map.getPortal("next00");
        assertNotNull(door, "the room must carry the next00 chain door");
        assertTrue(Math.abs(park.x - door.getPosition().x) > 8,
                "fixture: the post must be off the door, or the conflict cannot occur");

        Character bot = botOn(map, park);
        DriverlessMovementState.inject(bot);

        // 1) follow beat
        GCMovement.move(bot, door.getPosition().x, door.getPosition().y);
        assertEquals(door.getPosition().x, GCMovement.moveTargetOf(bot).x,
                "the follow beat must arm the door walk");

        // 2) the cleared-stage park beat, same tick
        PqActions.waitNearStageNpc(bot, LudiPqData.stageNpcId(1));

        Point after = GCMovement.moveTargetOf(bot);
        assertNotNull(after,
                "the park beat cancelled the door order: walkTo's 'already there' shortcut "
                        + "stopped the driver and threw away the follow order issued earlier in "
                        + "the same macro tick");
        assertEquals(door.getPosition().x, after.x, "the door order must survive unchanged");
    }

    /** The other half: a bot that has arrived on its OWN park walk must still be stood down. */
    @Test
    void theParkBeatStillStopsItsOwnOrder() {
        assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = realRoom();
        Character probe = botOn(map, new Point(0, 0));
        Point park = parkSpot(probe, post(probe));

        Character bot = botOn(map, park);
        DriverlessMovementState.inject(bot);

        GCMovement.move(bot, park.x, park.y);
        assertNotNull(GCMovement.moveTargetOf(bot), "the park order is live");

        PqActions.waitNearStageNpc(bot, LudiPqData.stageNpcId(1));
        assertEquals(null, GCMovement.moveTargetOf(bot),
                "a bot that has arrived on its own post must be stood down");
    }

    /**
     * The behaviour the blanket stop also provided, and which must NOT be lost: a puzzle area hold
     * pins the bot. {@code holdArea} arms the stage-walk shield after its walk, so the combat beat
     * cannot steer it off the crate - and with {@code stopIfTarget} the hold is the only producer
     * of a goal here, so it is still cancelled as before.
     */
    @Test
    void aPuzzleHoldStillPinsTheBot() {
        assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = realRoom();
        Character probe = botOn(map, new Point(0, 0));
        Point park = parkSpot(probe, post(probe));

        Character bot = botOn(map, park);
        DriverlessMovementState.inject(bot);

        GCMovement.move(bot, park.x, park.y);
        PqActions.holdArea(bot, park, 0);
        assertEquals(null, GCMovement.moveTargetOf(bot),
                "the hold must stand the bot down on its own spot");
        assertTrue(PqActions.movementShielded(BOT_ID),
                "the hold must also shield the spot from the combat beat's steering");
    }
}
