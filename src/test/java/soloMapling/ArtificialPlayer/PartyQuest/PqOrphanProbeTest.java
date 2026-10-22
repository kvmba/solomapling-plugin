package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
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
 * <p>The second half pins the recurring-re-arm contract: the NPC parks, the delivery walk
 * and the hand-off reclaim walk re-arm their walk every macro tick, and a re-arm issued
 * while the bot walks to a drop used to OVERWRITE the pickup walk mid-flight (walkTo only
 * refuses the re-arm while the bot still stands on its spot, so the moment it stepped off
 * to pick something up, the next re-arm dragged it back) - the root of the residual
 * orphan-pass ping-pong. A live move target that is not the re-arm's own spot must stand
 * the re-arm down (standsDownForLiveWalk, one seam for all three origins).
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
        // it via BotMovementProfile.fromCharacter inside the movement engine's entry settle.
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
        // Drop the entry directly (no GCMovement.disable): the arming paths walked GCMovement.move
        // against a Mockito mock, and disable() would dereference the mock outside any stubbing
        // window - the same reason the entry is injected driverless in the first place. Removing the
        // entry also clears the live move target, so no test inherits one. The probe throttle is
        // keyed by the shared BOT_ID too, so it is dropped here.
        soloMapling.ArtificialPlayer.GCMoveSystem.DriverlessMovementState.remove(BOT_ID);
        clearProbeAndShield();
        // The pickup walk also stamps the seizure window under the shared BOT_ID: without this
        // drop the next test inherits a live window and its loot tick silently refuses a walk.
        removePerBotEntry("pickupSeizedAtByBot");
    }

    /** Drop the probe throttle and the stage-walk shield, leaving the seizure window untouched. */
    private static void clearProbeAndShield() throws Exception {
        removePerBotEntry("orphanProbeAtByBot");
        removePerBotEntry("stageWalkShieldUntilByBot");
    }

    private static void removePerBotEntry(String fieldName) throws Exception {
        Field field = PqActions.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).remove(BOT_ID);
    }

    /** The live move goal of the bot's entry, read through the same-package test seam. */
    private static Point moveTargetOf(int botId) {
        return soloMapling.ArtificialPlayer.GCMoveSystem.DriverlessMovementState.moveTargetOf(botId);
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
        // Register the movement entry BEFORE any production arm path runs: GCMovement.move would
        // otherwise call enable() and start a real 50ms driver thread that invokes this mock
        // concurrently with the test's own stubbing (the flake's root cause: see DriverlessMovementState).
        soloMapling.ArtificialPlayer.GCMoveSystem.DriverlessMovementState.inject(bot);
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

    /**
     * The probe reaches the room's own extent. The old fixed 3_000px claimed "every quest room
     * fits inside it", but the shipped quest rooms do not: Ludi's towers are ~730x4575 and Orbis's
     * stage-1 cloud map is 2446x1486. A drop at a Ludi tower's far vertical end therefore sat
     * outside the probe and despawned unclaimed. The reach is now derived from the map's own
     * foothold extent, so no room can be bigger than its own probe.
     */
    @Test
    void theProbeReachesTheRoomsOwnFarCorner() throws Exception {
        MapleMap map = wzRoom();
        // The real room's extent, as the probe derives it: every walkable foothold's x/y span.
        org.gms.server.maps.FootholdTree tree = map.getFootholds();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (org.gms.server.maps.Foothold fh : tree.getAllFootholds()) {
            if (fh.isWall()) {
                continue;
            }
            minX = Math.min(minX, Math.min(fh.getX1(), fh.getX2()));
            maxX = Math.max(maxX, Math.max(fh.getX1(), fh.getX2()));
            minY = Math.min(minY, Math.min(fh.getY1(), fh.getY2()));
            maxY = Math.max(maxY, Math.max(fh.getY1(), fh.getY2()));
        }

        // Stand at one vertical extreme, drop a pass at the other: the probe must walk to it.
        Point botAt = new Point(Math.min(maxX, Math.max(minX, 0)), minY);
        Point dropAt = new Point(Math.min(maxX, Math.max(minX, 0)), maxY);
        Character bot = botOn(map, botAt);
        MapItem drop = new MapItem(new Item(PASS, (short) 0, (short) 1),
                dropAt, null, bot, null, (byte) 2, false);
        map.addMapObject(drop);

        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});

        assertEquals(dropAt, moveTargetOf(BOT_ID),
                "the probe must see a drop at the room's far vertical end (the old fixed 3_000px "
                        + "reach measured only " + (maxY - minY) + "px of room height here)");
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
    void aMidFightDropIsWalkedToEvenWhileTheChaseHoldsTheDriver() throws Exception {
        // The reported "bots kill, never loot": a drop lands outside the loot radius mid-fight,
        // the driver holds the chase's live goal, and the old bare !isMoving gate refused the
        // pickup walk on every macro tick of the whole fight - the chase re-issues its goal
        // every combat beat, so the bot never stops moving and the drop despawns. The seizure
        // gate takes the driver at a paced cadence instead (the seek beat re-takes the
        // steering the moment the walk lands).
        MapleMap map = roomWithDropAt(new Point(0, 0), false);
        Character bot = botOn(map, new Point(600, 0));

        // The chase in flight: a live driver goal that is NOT the drop (the seek beat's shape).
        GCMovement.move(bot, 700, 0);
        assertNotNull(moveTargetOf(BOT_ID), "precondition: the chase holds the driver");

        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});

        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID),
                "a mid-fight drop must be walked to - the paced seizure takes the driver off the chase");
        assertEquals(0, PqActions.countItem(bot, PASS), "visibility only - nothing picked up");
    }

    @Test
    void theSeizureIsPacedSoAnUnreachableDropCannotPingPongTheDriver() throws Exception {
        // The 317bfbc shape the old isMoving gate existed for: an unreachable drop must not
        // turn the driver into a loot/seek tug-of-war. After one seizure the chase keeps the
        // driver for the whole window - the very next loot tick must NOT re-arm the walk.
        MapleMap map = roomWithDropAt(new Point(0, 0), false);
        Character bot = botOn(map, new Point(600, 0));

        GCMovement.move(bot, 700, 0); // the chase holds the driver
        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID), "first tick: the seizure lands");

        // The pickup walk armed an 8s stage-walk shield, which would short-circuit
        // pickupMaySeizeDriver before the 6s seizure window is read. Drop it (and the probe
        // throttle) here so this assertion pins the seizure pacing itself, not the shield.
        clearProbeAndShield();

        // The walk is abandoned (unreachable target), the seek re-arms the chase...
        GCMovement.move(bot, 700, 0);
        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});

        assertEquals(new Point(700, 0), moveTargetOf(BOT_ID),
                "inside the seizure window the chase keeps the driver - no loot/seek ping-pong");
    }

    @Test
    void anEmptyProbeDoesNotBurnTheSeizureWindow() throws Exception {
        // The window is burned only when a walk ACTUALLY replaces the chase: an empty probe
        // walks nowhere and arms no shield, so it must leave the window free. Otherwise a
        // drop that lands inside the caller's radius seconds later stays locked out.
        MapleMap map = wzRoom(); // no drop yet
        Character bot = botOn(map, new Point(600, 0));

        GCMovement.move(bot, 700, 0); // the chase holds the driver

        // Empty probe: the old code stamped the seizure window here despite walking nowhere.
        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});
        assertEquals(new Point(700, 0), moveTargetOf(BOT_ID),
                "precondition: the empty probe leaves the chase alone and arms no shield");

        // The drop lands inside the next loot's own radius (loot sees it directly) but beyond
        // pickup reach (it must be walked to) - the shape an empty probe used to lock out.
        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(1);
        when(owner.getPartyId()).thenReturn(-1);
        map.addMapObject(new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(0, 0), null, owner, null, (byte) 2, false));

        PqActions.loot(bot, bot.getPosition(), 700, new int[]{PASS});

        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID),
                "an empty probe must not burn the 6s window: the in-radius, out-of-reach "
                        + "drop is walked to");
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
    void theDeliveryReArmNeverOverwritesALivePickupWalk() throws Exception {
        MapleMap map = roomWithDropAt(new Point(0, 0), false);
        Character bot = botOn(map, new Point(600, 0));

        // The probe arms the pickup walk (the shape pinned above).
        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID));

        // The bot holds passes, so the delivery path has something to hand over. doReturn,
        // not when(): the deep-stubs mock has already served intermediate calls.
        org.mockito.Mockito.doReturn(3).when(bot).getItemQuantity(PASS, false);

        // Same tick, deliverAndPark's next step fires the delivery re-arm (loot -> handItems).
        // It must stand down: the live move target (the drop) is not the delivery post.
        int dropped = PqActions.handItemsToLeaderAfterStage(bot, PASS, STAGE_NPC);

        assertEquals(0, dropped, "nothing may be delivered while a live move owns the driver");
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID),
                "the pickup walk must survive the delivery re-arm (walkTo would have overwritten it)");
    }

    @Test
    void theReclaimWalkNeverOverwritesALivePickupWalk() throws Exception {
        // Two drops: an ordinary pass at (0,0) the probe walks to, and a 30s-old addressed
        // hand-off pile at (300,0) the reclaim would walk back to. The live move must survive
        // the reclaim re-arm (without the gate, its walkForPickup overwrote the target).
        MapleMap map = wzRoom();
        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(1);
        when(owner.getPartyId()).thenReturn(-1);
        Character bot = botOn(map, new Point(600, 0));
        MapItem loose = new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(0, 0), null, owner, null, (byte) 2, false);
        // THIS bot is the pile's dropper: the reclaim only walks back its own hand-offs.
        MapItem addressed = new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(300, 0), bot, owner, null, (byte) 2, false);
        // dropTime 0 = never collected, so the 30s reclaim window has long passed.
        addressed.setPermanentOwner(true);
        map.addMapObject(loose);
        map.addMapObject(addressed);

        PqActions.loot(bot, bot.getPosition(), 260, new int[]{PASS});
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID));

        // The pile's owner is this bot's party leader, so the reclaim would claim it.
        armPartyLeader(bot, owner);

        int recovered = PqActions.recoverUngatheredHandoffs(bot, PASS);

        assertEquals(0, recovered, "the reclaim may not pick a pile it has not walked to");
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID),
                "the pickup walk must survive the reclaim re-arm too");
    }

    @Test
    void aTeammatesHandOffPileIsNotReclaimedByThisBot() throws Exception {
        // The reclaim is each bot's OWN insurance against the leader's 3-minute despawn window,
        // not a shared sweep: a pile dropped by ANOTHER bot for the same leader must be left to
        // that bot - reclaiming it here only shuffles the stock between bots.
        MapleMap map = wzRoom();
        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(1);
        when(owner.getPartyId()).thenReturn(-1);
        Character bot = botOn(map, new Point(600, 0));
        Character teammate = mock(Character.class, RETURNS_DEEP_STUBS);
        when(teammate.getId()).thenReturn(BOT_ID + 1);
        MapItem addressed = new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(0, 0), teammate, owner, null, (byte) 0, false);
        addressed.setPermanentOwner(true);
        addressed.setDropTime(0); // 30s+ old: the reclaim window has long passed
        map.addMapObject(addressed);
        // Without the dropper filter this exact shape is what the reclaim walked to.
        armPartyLeader(bot, owner);

        int recovered = PqActions.recoverUngatheredHandoffs(bot, PASS);

        assertEquals(0, recovered);
        assertNull(moveTargetOf(BOT_ID),
                "a teammate's addressed pile must not be walked to or reclaimed by this bot");
    }

    @Test
    void thisBotsOwnHandOffPileIsWalkedBack() throws Exception {
        // Positive control for the dropper filter: the same shape with THIS bot as the dropper
        // still arms the walk-back - the filter must not silently disable the whole reclaim.
        MapleMap map = wzRoom();
        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(1);
        when(owner.getPartyId()).thenReturn(-1);
        Character bot = botOn(map, new Point(600, 0));
        MapItem addressed = new MapItem(new Item(PASS, (short) 0, (short) 1),
                new Point(0, 0), bot, owner, null, (byte) 0, false);
        addressed.setPermanentOwner(true);
        addressed.setDropTime(0); // 30s+ old: the reclaim window has long passed
        map.addMapObject(addressed);
        armPartyLeader(bot, owner);

        int recovered = PqActions.recoverUngatheredHandoffs(bot, PASS);

        assertEquals(0, recovered, "still out of reach: the pickup happens on a later tick");
        assertEquals(new Point(0, 0), moveTargetOf(BOT_ID),
                "this bot's own hand-off pile is the one the reclaim walks back");
    }

    /** Wire {@code bot.getParty()} so {@link PqActions#partyLeader(Character)} resolves to {@code leader}. */
    private static void armPartyLeader(Character bot, Character leader) {
        PartyCharacter leaderEntry = mock(PartyCharacter.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.doReturn(leader).when(leaderEntry).getPlayer();
        Party party = mock(Party.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.doReturn(leaderEntry).when(party).getLeader();
        org.mockito.Mockito.doReturn(party).when(bot).getParty();
    }

    @Test
    void theStandDownGateHoldsTheContract() {
        // Pure decision table of standsDownForLiveWalk (see PqActions' javadoc): a live goal
        // that is not the re-arm's own spot stands the re-arm down; no goal, or a goal AT the
        // re-arm's spot (the bot is walking home already), does not.
        Point drop = new Point(0, 0);
        Point park = new Point(-157, -192);
        assertTrue(PqActions.standsDownForLiveWalk(true, new Point(600, 0), park),
                "walking to a drop: the re-arm must stand down (it would overwrite the walk)");
        assertFalse(PqActions.standsDownForLiveWalk(false, new Point(600, 0), park),
                "no live goal (driver gave the move up): the re-arm must still re-issue");
        assertFalse(PqActions.standsDownForLiveWalk(true, new Point(-160, -190), park),
                "the bot is walking (or standing) on its own spot: no conflict, no stand-down");
        assertFalse(PqActions.standsDownForLiveWalk(true, null, park),
                "a position-less bot cannot be judged near anything: the re-arm proceeds");
    }
}
