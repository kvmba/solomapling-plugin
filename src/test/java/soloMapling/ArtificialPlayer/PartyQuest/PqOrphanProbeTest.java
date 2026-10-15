package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The room-wide orphan-drop probe of {@link PqActions#loot} and the park re-arm contract.
 *
 * <p>The 2000px cleanup sweep f4043a1 deleted covered one thing its 260px kill-neighbourhood
 * replacement never re-homed: a drop that lands OUTSIDE every bot's loot radius (a ranged
 * kill's fall point). With no sweep, the drop is invisible to every tick's scan and the
 * 3-minute item_expire despawn eats the last pass of a 25-pass stage. The probe restores the
 * VISIBILITY half - it may only start a WALK to the drop (the engine's pickupItem has no
 * distance check, so a grab from here would teleport it), and it must never target an
 * addressed hand-off or another bot's still-protected drop (the pass ping-pong).
 *
 * <p>The second half pins the park seams: waitNearStageNpc/spreadNearStageNpc re-arm their
 * walk every macro tick, and a re-arm issued while the bot walks to a drop used to OVERWRITE
 * the pickup walk mid-flight (walkTo only refuses the re-arm while the bot still stands on
 * its spot, so the moment it stepped off to pick something up, the next park re-arm dragged
 * it back) - the root of the residual orphan-pass ping-pong. A live move target that is not
 * the park spot must stand the park down.
 *
 * <p>Both halves drive the real {@link PqActions} decision logic against a live {@link
 * MapleMap} and a real {@link GCMovement} state entry (the same state-injection pattern the
 * GCMoveSystem execution tests use); the walk is observed through {@code st.moveTarget},
 * exactly what the park seam's {@code hasMoveTarget} gate reads.
 */
class PqOrphanProbeTest {

    private static final int PASS = 4001022;
    private static final int STAGE_NPC = 2040036;
    private static final int BOT_ID = 9501;

    /** The park spot the WZ life entry gives the stage-1 turn-in NPC. */
    private static final Point STAGE_NPC_SPOT = new Point(-157, -192);

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        // Job's <clinit> resolves names through the host i18n sources; the walkTo path reaches
        // it via BotMovementProfile.fromCharacter inside GCMovement.enable.
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("messageSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("logSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("exceptionSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    @AfterEach
    void releaseBotState() throws Exception {
        // disable (not a bare STATES.remove): the arming paths walked GCMovement.enable,
        // which STARTED a real self-rescheduling driver for this entry - only disable's
        // finishDisable -> GCMovementDriver.stop cancels it. It also clears the live move
        // target, so no test inherits one. The probe throttle is keyed by the shared BOT_ID
        // too, so it is dropped here (there is no public seam for it).
        GCMovement.disable(botRef);
        Field throttle = PqActions.class.getDeclaredField("orphanProbeAtByBot");
        throttle.setAccessible(true);
        ((Map<?, ?>) throttle.get(null)).remove(BOT_ID);
    }

    @AfterAll
    static void releaseBotStateFinal() {
        GCMovement.disable(botRef);
    }

    /** The latest bot mock, for the teardown's disable (see releaseBotState). */
    private static Character botRef;

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> states() throws Exception {
        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        return (Map<Integer, Object>) f.get(null);
    }

    /** The live move goal of the bot's driver state, read reflectively (the state is package-private). */
    private static Point moveTargetOf(int botId) throws Exception {
        Object st = states().get(botId);
        if (st == null) {
            return null;
        }
        Field f = st.getClass().getDeclaredField("moveTarget");
        f.setAccessible(true);
        return (Point) f.get(st);
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

    private static MapleMap roomWithDropAt(Point at, boolean addressed) {
        MapleMap map = wzRoom();
        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(1);
        when(owner.getPartyId()).thenReturn(-1);
        MapItem drop = new MapItem(new Item(PASS, (short) 0, (short) 1),
                at, null, owner, null, (byte) 2, false);
        if (addressed) {
            drop.setPermanentOwner(true);
        }
        map.addMapObject(drop);
        return map;
    }

    /**
     * The real 922010100 room: geometry from the WZ (GCMovement.enable, walked by every
     * arming path, builds the foothold index - a foothold-less synthetic map would NPE) plus
     * the stage's own turn-in NPC 2040036 at its WZ life position (-157,-192), so the park
     * seams actually resolve a spot instead of returning early on a missing NPC. Skipped
     * (assumption) on machines without the wz tree, like every WZ-backed test in this package.
     */
    private static MapleMap wzRoom() {
        String mapPath = "Map/Map" + (922010100 / 100000000) + "/" + 922010100 + ".img";
        Data mapData = org.gms.provider.DataProviderFactory.getDataProvider(WZFiles.MAP)
                .getData(mapPath);
        org.junit.jupiter.api.Assumptions.assumeTrue(mapData != null,
                "Map.wz not resolvable from this working directory");
        MapleMap map = new MapleMap(922010100, 0, 0, 922010100, 0.0f);
        map.setMapLineBoundings(-4000, 600, -3000, 3000);
        Data footholdData = mapData.getChildByPath("foothold");
        org.gms.server.maps.FootholdTree tree = new org.gms.server.maps.FootholdTree(
                new Point(-4000, -4000), new Point(4000, 4000));
        for (Data footRoot : footholdData) {
            for (Data footCategory : footRoot) {
                for (Data fh : footCategory) {
                    int x1 = DataTool.getInt(fh.getChildByPath("x1"));
                    int y1 = DataTool.getInt(fh.getChildByPath("y1"));
                    int x2 = DataTool.getInt(fh.getChildByPath("x2"));
                    int y2 = DataTool.getInt(fh.getChildByPath("y2"));
                    tree.insert(new org.gms.server.maps.Foothold(
                            new Point(x1, y1), new Point(x2, y2), Integer.parseInt(fh.getName())));
                }
            }
        }
        map.setFootholds(tree);
        Data life = mapData.getChildByPath("life");
        if (life != null) {
            for (Data entry : life) {
                if (!"n".equals(DataTool.getString("type", entry, ""))
                        || DataTool.getIntConvert("id", entry, 0) != STAGE_NPC) {
                    continue;
                }
                org.gms.server.life.NPC npc = new org.gms.server.life.NPC(STAGE_NPC,
                        new org.gms.server.life.NPCStats("npc" + STAGE_NPC));
                npc.setPosition(new Point(DataTool.getInt(entry.getChildByPath("x")),
                        DataTool.getInt(entry.getChildByPath("y"))));
                map.addMapObject(npc);
            }
        }
        return map;
    }

    private static Character botOn(MapleMap map, Point at) {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getPosition()).thenReturn(at);
        when(bot.getPartyId()).thenReturn(-1);
        botRef = bot;
        return bot;
    }

    @Test
    void aDropBeyondTheLootRadiusIsWalkedToOnTheProbedSweep() throws Exception {
        MapleMap map = roomWithDropAt(new Point(0, 0), false);
        Character bot = botOn(map, new Point(600, 0));

        // A quiet-room 260px sweep: the drop at 600px is invisible to the caller's own radius,
        // which is exactly the orphaned shape the deleted cleanup sweep used to cover.
        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});

        assertTrue(GCMovement.hasMoveTarget(bot),
                "the probe must start a WALK to the out-of-radius drop (never a grab: the engine's "
                        + "pickupItem has no distance check and would teleport it)");
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID), "the walk aims at the drop");
        assertEquals(0, PqActions.countItem(bot, PASS), "visibility only - nothing picked up");
    }

    @Test
    void theProbeIsThrottledPerBot() throws Exception {
        MapleMap map = roomWithDropAt(new Point(0, 0), false);
        Character bot = botOn(map, new Point(600, 0));

        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID));

        GCMovement.stop(bot); // the walk landed / was dropped; the throttle window still runs
        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});
        assertNull(moveTargetOf(BOT_ID), "a throttled probe must not re-arm the walk");
    }

    @Test
    void anAddressedHandOffPileIsNeverTheProbeTarget() throws Exception {
        MapleMap map = roomWithDropAt(new Point(0, 0), true);
        Character bot = botOn(map, new Point(600, 0));

        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});

        assertFalse(GCMovement.hasMoveTarget(bot),
                "the party's stock posted for the leader must stay untouched - the ping-pong guard");
    }

    @Test
    void anotherBotsStillProtectedDropIsNeverTheProbeTarget() throws Exception {
        // The second entitlement half: botCanLoot refuses another bot's owner-protected
        // (dropType 0) drop inside its 15s FFA window - isFloorLoot does NOT (qty 1, no
        // permanent owner), so only this shape pins the botCanLoot gate. A loot sweep that
        // walked to a still-protected drop would steal it the moment the bot got close
        // (the engine's pickup re-checks nothing the probe already filtered).
        MapleMap map = wzRoom();
        Character other = mock(Character.class, RETURNS_DEEP_STUBS);
        when(other.getId()).thenReturn(777);
        when(other.getPartyId()).thenReturn(-1);
        MapItem protectedDrop = new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(0, 0), null, other, null, (byte) 0, false);
        // A real drop is stamped at spawn; without a stamp the FFA window reads as long
        // expired (dropTime 0) and the drop is ordinary loot. Stamp "now" to exercise the
        // still-protected branch of botCanLoot.
        protectedDrop.setDropTime(System.currentTimeMillis());
        map.addMapObject(protectedDrop);
        Character bot = botOn(map, new Point(600, 0));

        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});

        assertFalse(GCMovement.hasMoveTarget(bot),
                "another bot's still-protected drop must stay untouched - no walk toward it");
    }

    @Test
    void aParkReArmNeverOverwritesALivePickupWalk() throws Exception {
        MapleMap map = roomWithDropAt(new Point(0, 0), false);
        Character bot = botOn(map, new Point(600, 0));

        // The probe arms the pickup walk (the shape pinned above).
        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID));

        // The bot has now LEFT its spot (the walk moved it), so walkTo's stand-on-target
        // short-circuit no longer applies - this is the exact tick the old park re-arm
        // overwrote the pickup walk on. doReturn, not when(): the deep-stubs mock has already
        // served intermediate calls (getJob inside GCMovement.enable), and re-stubbing with
        // when() would run the real deep-stub chain against it.
        org.mockito.Mockito.doReturn(new Point(540, 0)).when(bot).getPosition();

        // The parked-by-NPC re-arm fires the same tick. It must stand down: the live move
        // target (the drop) is not the park spot.
        PqActions.waitNearStageNpc(bot, 2040036);
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID),
                "the pickup walk must survive the park re-arm (walkTo would have overwritten it)");

        // The ring re-arm honours the same contract.
        PqActions.spreadNearStageNpc(bot, 2040036);
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID),
                "the pickup walk must survive the ring re-arm too");
    }

    @Test
    void theParkStandDownGateHoldsTheContract() {
        // Pure decision table of parkStandsDown (see PqActions' javadoc): a live goal that is
        // not the park spot stands the park down; no goal, or a goal AT the park spot (the
        // bot is walking home already), does not.
        Point drop = new Point(0, 0);
        Point park = new Point(-157, -192);
        assertTrue(PqActions.parkStandsDown(true, new Point(600, 0), park),
                "walking to a drop: the park re-arm must stand down (it would overwrite the walk)");
        assertFalse(PqActions.parkStandsDown(false, new Point(600, 0), park),
                "no live goal (driver gave the move up): the park re-arm must still re-issue");
        assertFalse(PqActions.parkStandsDown(true, new Point(-160, -190), park),
                "the bot is walking (or standing) on its own park spot: no conflict, no stand-down");
        assertFalse(PqActions.parkStandsDown(true, null, park),
                "a position-less bot cannot be judged near anything: the re-arm proceeds");
    }
}
