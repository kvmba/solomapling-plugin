package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Behavioural regressions for the LPQ stage fixes, run against the REAL WZ geometry through the
 * production movement driver.
 *
 * <p>Each test pins one root cause that a unit-level assertion cannot see:
 * <ul>
 *   <li>stage 9 - a ranged kit must reach a FIRING ledge and never walk at the unreachable altar
 *       ledge, on the combat beat as well as on the macro tick;</li>
 *   <li>stage 5 - the hide must follow the golem's real body box, on the main map as well as in
 *       the seven-golem room;</li>
 *   <li>stage 1 - a pass inside the loot radius must take the driver before the chase does;</li>
 *   <li>the bonus tail room - the bot must walk to 2040035 instead of standing still.</li>
 * </ul>
 */
class LudiStageRootCauseTest {

    private static final int BOT_ID = 892001;

    @TempDir
    static Path navigationCache;
    private static Path originalCacheDir;

    private Character bot;
    private BotMovementState state;

    @BeforeAll
    static void bootHost() throws Exception {
        originalCacheDir = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(navigationCache);
        ApplicationContext context = mock(ApplicationContext.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.gms.service.ConfigService config = mock(org.gms.service.ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(org.gms.service.ConfigService.class)).thenReturn(config);
        org.gms.property.ServiceProperty properties = mock(org.gms.property.ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(org.gms.property.ServiceProperty.class)).thenReturn(properties);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, org.springframework.context.MessageSource.class))
                    .thenReturn(messages);
        }
        Method setter = org.gms.manager.ServerManager.class.getDeclaredMethod(
                "setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new org.gms.manager.ServerManager(), context);
        org.gms.dao.entity.GameConfigDO interval = new org.gms.dao.entity.GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        org.gms.config.GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreCacheDir() {
        BotNavigationGraphProvider.setCacheDir(originalCacheDir);
    }

    @BeforeEach
    void useVirtualClock() {
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterEach
    void release() throws Exception {
        states().remove(BOT_ID);
        PqActions.clearSeekState(BOT_ID);
        LudiStages.releaseRoomState(BOT_ID);
        BotAttackDriver.clearBot(BOT_ID);
        BotAuraState.clearBot(BOT_ID);
        MovementClock.reset();
    }

    // =========================================================================
    // Stage 9 - the altar ledge has no inbound edge
    // =========================================================================

    /**
     * The altar ledge (922010900 region 3, x 554..636 at y=0) has NO inbound edge in the map
     * data: the only way to summon Alishar is a Ratz dying ON it so its 4001022 lands inside the
     * crystal's trigger box. A ranged kit therefore has to shoot from the ledge below; the chase
     * must never emit the altar floor as a goal.
     *
     * <p>The bug this pins: {@code reachableOnly} was set only inside {@code workStage} (macro
     * tick, 36-48s unobserved), while the 250ms combat beat ran the unrestricted overload. A bot
     * entering the room before its first macro tick walked to the floor directly under the altar
     * and stalled there.
     */
    @Test
    void stage9RangedKitOnlyEverAimsAtAReachableFiringLedge() throws Exception {
        MapleMap map = realMap(922010900);
        assumeTrue(map != null, "GMS083 Map.wz required");
        createBot(map, new Point(-171, 178), Job.HUNTER);
        Monster ratz = monster(9300006, 998001, new Point(588, 0));
        inject(map, ratz);

        Set<Point> goals = new LinkedHashSet<>();
        boolean reached = false;
        // Deliberately NEVER call workStage/fightBoss: this is the combat beat alone, which is
        // what runs for the first 36-48s on an unobserved map.
        for (int beat = 0; beat < 400 && !reached; beat++) {
            // Real beat order: the policy is refreshed, then the chase runs. applyChasePolicy
            // itself must NOT steer (that is what makes it safe to call outside the beat's gates).
            LudiStages.applyChasePolicy(bot);
            PqActions.seekAndAttack(bot);
            if (state.moveTarget != null) {
                goals.add(new Point(state.moveTarget));
            }
            reached = BotAttackDriver.canReach(bot, ratz);
            tick();
        }

        assertTrue(reached, "a ranged kit must reach a firing position for the altar Ratz; goals=" + goals);
        Point altarFloor = new Point(588, 184);
        for (Point goal : goals) {
            assertFalse(goal.y < altarFloor.y && Math.abs(goal.x - 588) <= 60,
                    "the chase must never aim at the unreachable altar ledge above it; goal=" + goal);
        }
    }

    // =========================================================================
    // Stage 5 - the hide follows the golem's real body box
    // =========================================================================

    /**
     * 9300013 links to 4230110, whose stand frame is lt=(-67,-139) rb=(84,0): a bot is inside the
     * golem's touch box from 139px above its feet, while the retired policy used a 110px y band.
     * The 29px between them was walked unhidden.
     */
    @Test
    void stage5HideIsArmedFromTheGolemsBodyBoxNotAYBand() throws Exception {
        MapleMap map = realMap(922010506);
        assumeTrue(map != null, "GMS083 Map.wz required");
        // 125px above a golem row: inside its body box, outside the retired 110px band.
        createBot(map, new Point(0, -443 - 125), Job.THIEF);
        inject(map, monster(9300013, 998002, new Point(0, -443)));

        try (var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(eq(bot), anyInt())).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, inv.getArgument(1));
                return 120_000;
            });
            tick();
        }
        assertTrue(BotAuraState.isMonsterImmune(bot),
                "a body 125px above the golem's feet is inside its hitbox and must be hidden");
    }

    /**
     * The main room (922010500) seats two golems on its ground floor, and every sub-room exit
     * lands at the bottom of the tower while next00 sits at the top - so returning means crossing
     * that floor. The policy only recognised 922010506, so a THIEF carrying 隐身术 crossed it
     * with no protection at all.
     */
    @Test
    void stage5HideAlsoCoversTheMainRoomsGroundFloor() throws Exception {
        MapleMap map = realMap(922010500);
        assumeTrue(map != null, "GMS083 Map.wz required");
        createBot(map, new Point(26, 55), Job.THIEF);
        inject(map, monster(9300013, 998003, new Point(26, 55)));

        try (var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(eq(bot), anyInt())).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, inv.getArgument(1));
                return 120_000;
            });
            tick();
        }
        assertTrue(BotAuraState.isMonsterImmune(bot),
                "922010500's ground floor seats the same invincible guards as the guard room");
    }

    // =========================================================================
    // Stage 1 - pickup takes the driver before the chase
    // =========================================================================

    /**
     * Measured before this fix: a pass lying 200px away on the bot's own floor stayed unpicked for
     * 24 macro beats while the bot climbed 800px away to a mob. looting and chasing were two calls
     * in that order, and loot()'s walk branch yields to the chase order the first call had just
     * issued - so the branch never ran and only the 60px floor sweep remained.
     */
    @Test
    void stage1ALootablePassTakesTheDriverAwayFromTheChase() throws Exception {
        MapleMap map = realMap(922010100);
        assumeTrue(map != null, "GMS083 Map.wz required");
        createBot(map, new Point(-171, 184), Job.HUNTER);
        Point dropPos = new Point(29, 184); // 200px away: past PICKUP_REACH, inside LOOT_RADIUS
        inject(map, item(998010, dropPos));
        inject(map, monster(9300005, 998011, new Point(120, -600)));

        assertTrue(PqActions.pickupBeforeChase(bot, 260, new int[]{LudiPqData.PASS}),
                "a pass inside the loot radius must take the driver this beat");
        org.junit.jupiter.api.Assertions.assertEquals(dropPos, state.moveTarget,
                "the issued goal must be the pass, not the mob");
        // Scope note, deliberately explicit: this test covers ISSUING the order. The gate that
        // keeps that order alive across the 4Hz combat beat lives in PartyQuestBot.grindTick (the
        // only caller that knows the quest's own steppedOnLootIds). LudiPickupGateTest pins the two
        // read-only seams that gate is built on, and PqPickupGateWiringTest pins that the beat
        // consults it before letting the chase steer - but NO test here drives grindTick end to
        // end, because a real PartyQuestBot cannot be constructed against a mocked Character.
        assertFalse(PqActions.itemsOnFloorAt(bot, state.moveTarget, new int[]{LudiPqData.PASS}).isEmpty(),
                "the driver's goal must still be a scoopable pass for the beat's gate to see it");
    }

    // =========================================================================
    // Bonus tail room - the exit is the reward NPC
    // =========================================================================

    /**
     * 922011100 holds no boxes at all: only 2040035 and 9000033. Sending it through the box loop
     * made the bot stand still forever, and the room is inside the quest's map range so the lobby
     * beat does not reclaim it either.
     *
     * <p>What the bot owes the room is the OBSERVABLE exit: walk to the NPC, spend a beat there,
     * then leave. It must never run the NPC's script - that grants the bot an event reward and,
     * on a full inventory, leaves the dialog open with nothing able to close it.
     */
    @Test
    void bonusTailRoomWalksToTheNpcThenLeavesWithoutTalking() throws Exception {
        MapleMap map = realMap(922011100);
        assumeTrue(map != null, "GMS083 Map.wz required");
        Point st00 = map.getPortal("st00").getPosition();
        createBot(map, st00, Job.HUNTER);

        assertFalse(LudiStages.takeBonusReward(bot), "the beat starts with the walk, not with a warp");
        assertNotNull(state.moveTarget, "the tail room's exit is a walk to 2040035, not idling");
        Point npc = LudiPqData.BONUS_TAIL_NPC_SPOT;
        assertTrue(Math.abs(state.moveTarget.x - npc.x) <= 16
                        && Math.abs(state.moveTarget.y - npc.y) <= 16,
                "the goal must be the reward NPC; got " + state.moveTarget + " for npc " + npc);

        // Walk there for real, then confirm the beat completes on its own after the pause.
        for (int beat = 0; beat < 600 && !arrived(npc); beat++) {
            LudiStages.takeBonusReward(bot);
            tick();
        }
        assertTrue(arrived(npc), "the NPC must be reachable by walking; ended at " + bot.getPosition());
        assertFalse(LudiStages.takeBonusReward(bot), "the conversation beat is not instant");

        long until = System.currentTimeMillis() + 4_000L;
        boolean done = false;
        while (System.currentTimeMillis() < until && !done) {
            done = LudiStages.takeBonusReward(bot);
            if (!done) {
                Thread.sleep(200);
            }
        }
        assertTrue(done, "the beat must end on its own so the bot can leave the run");
    }

    private boolean arrived(Point npc) {
        Point p = bot.getPosition();
        return p != null && Math.abs(p.x - npc.x) <= 24 && Math.abs(p.y - npc.y) <= 24;
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private void tick() throws Exception {
        ObserverTracker.markObservedNow(bot.getMapId());
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        try {
            tick.invoke(null, state);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw e;
        }
        MovementClock.advance(250);
    }

    private void createBot(MapleMap map, Point initial, Job job) throws Exception {
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        java.util.concurrent.atomic.AtomicReference<Point> position =
                new java.util.concurrent.atomic.AtomicReference<>(new Point(initial));
        bot = mock(Character.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(map.getId());
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        when(bot.getParty()).thenReturn(null);
        when(bot.getEventInstance()).thenReturn(null);
        when(bot.getInventory(any())).thenReturn(mock(org.gms.client.inventory.Inventory.class));
        when(bot.getClient().getChannel()).thenReturn(1);
        when(bot.getClient().getWorld()).thenReturn(0);
        doAnswer(inv -> {
            position.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        state = new BotMovementState(bot, null);
        state.movementProfile = BotMovementProfile.base();
        state.lastMap = map;
        state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(state, bot, initial);
        states().put(BOT_ID, state);
    }

    private static MapleMap realMap(int id) {
        var source = org.gms.provider.DataProviderFactory.getDataProvider(
                org.gms.provider.wz.WZFiles.MAP);
        if (source.getData(BotNavigationMapLoader.getMapName(id)) == null) {
            return null;
        }
        return BotNavigationMapLoader.loadMapGeometry(id);
    }

    private static MapObject npc(int id, int oid, Point position) throws Exception {
        var npc = new org.gms.server.life.NPC(id, null);
        npc.setPosition(position);
        Field oidField = org.gms.server.maps.MapObject.class.getDeclaredField("objectId");
        oidField.setAccessible(true);
        oidField.setInt(npc, oid);
        return npc;
    }

    private static Monster monster(int id, int oid, Point position) {
        Monster mob = mock(Monster.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(mob.getId()).thenReturn(id);
        when(mob.getObjectId()).thenReturn(oid);
        when(mob.getType()).thenReturn(MapObjectType.MONSTER);
        when(mob.getPosition()).thenReturn(position);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getName()).thenReturn("regression target");
        return mob;
    }

    /**
     * A real MapItem: {@code getItemId()} is final on the host type, so a Mockito double cannot
     * answer it and every filter downstream would see 0.
     */
    private static MapObject item(int oid, Point position) {
        var item = new org.gms.client.inventory.Item(LudiPqData.PASS, (short) 1, (short) 1);
        var owner = mock(Character.class);
        when(owner.getId()).thenReturn(BOT_ID + 1);
        when(owner.getPartyId()).thenReturn(-1);
        var drop = new org.gms.server.maps.MapItem(item, position, null, owner, null, (byte) 2, false);
        drop.setDropTime(MovementClock.nowMs() - 5_000L);
        drop.setObjectId(oid);
        return drop;
    }

    @SuppressWarnings("unchecked")
    private static void inject(MapleMap map, MapObject object) throws Exception {
        Field objects = MapleMap.class.getDeclaredField("mapobjects");
        objects.setAccessible(true);
        ((Map<Integer, MapObject>) objects.get(map)).put(object.getObjectId(), object);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        Field states = GCMovement.class.getDeclaredField("STATES");
        states.setAccessible(true);
        return (Map<Integer, BotMovementState>) states.get(null);
    }
}
