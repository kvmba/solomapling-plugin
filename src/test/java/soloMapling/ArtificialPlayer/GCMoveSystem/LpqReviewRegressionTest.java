package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.game.CharacterStance;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.packet.Packet;
import org.gms.property.ServiceProperty;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDemonRider;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotEnergyCharge;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotFinalAttack;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotGroundMists;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotMesoBomb;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotShadowMeso;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotShadowWeb;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotGrindSystem.CombatSpacing;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPQBot;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiPqData;
import soloMapling.ArtificialPlayer.BotTypes.Ludi.LudiStages;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.MapVFX.CustomReactor;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects.StrikeResult.*;

/** Behavioural regressions from the review of 2f37975 / 17d4145. */
class LpqReviewRegressionTest {
    private static final int BOT_ID = 891001;
    private Character bot;
    private BotMovementState state;
    @TempDir static Path navigationCache;
    private static Path originalNavigationCache;

    @BeforeAll
    static void bootHost() throws Exception {
        originalNavigationCache = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(navigationCache);
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, org.springframework.context.MessageSource.class)).thenReturn(messages);
        }
        Method setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server"); interval.setConfigSubType("0");
        interval.setConfigCode("update_interval"); interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreNavigationCache() {
        BotNavigationGraphProvider.setCacheDir(originalNavigationCache);
    }

    @BeforeEach
    void useClock() {
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterEach
    void releaseState() throws Exception {
        states().remove(BOT_ID);
        PqActions.clearSeekState(BOT_ID);
        PqActions.clearReactorBeat(BOT_ID);
        LudiStages.releaseRoomState(BOT_ID);
        BotAttackDriver.clearBot(BOT_ID);
        BotAuraState.clearBot(BOT_ID);
        BotEnergyCharge.clearBot(BOT_ID);
        MovementClock.reset();
    }

    @Test
    void aSecondFallIntoTheSamePitIsRescuedAfterCooldown() throws Exception {
        MapleMap map = map(922019971);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -500, 100);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        state.moveTarget = new Point(0, -500);
        assertTrue(BotMovementManager.selfHealDeadPit(state));
        assertEquals(new Point(0, -500), bot.getPosition());

        BotPhysicsEngine.teleportTo(state, bot, new Point(0, 0));
        BotMovementManager.resetEntryStateAfterTeleport(state);
        state.moveTarget = new Point(0, -500);
        assertFalse(BotMovementManager.selfHealDeadPit(state), "rescue cooldown still applies");
        state.unstuckCooldownMs = 0;
        assertTrue(BotMovementManager.selfHealDeadPit(state), "a cached dead verdict still requires rescue");
        assertEquals(new Point(0, -500), bot.getPosition());
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"BEGINNER", "HUNTER", "CLERIC"})
    void anAttackPausesTheLiveChaseAndResumesAfterTheAnimation(Job job) throws Exception {
        MapleMap map = map(922019994);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), job);
        state.alertResetScheduled = true; // virtual time: no real delayed alert-reset task
        AtomicInteger arrivals = new AtomicInteger();
        GCMovement.move(bot, 600, 0, arrivals::incrementAndGet);
        state.moveDir = 1;
        BotPhysicsEngine.applyGroundMotion(state, bot, BotPhysicsEngine.findGroundFoothold(map, bot.getPosition()));
        assertNotEquals(0, state.movementVelX, "start with a real walk already in flight");
        Point attackPosition = new Point(bot.getPosition());
        inject(map, monster(9300005, 99150, new Point(attackPosition.x + 50, 0)));

        assertTrue(BotAttackDriver.forceSingle(bot).hit());
        assertEquals(0.0, state.hspeed);
        assertEquals(0, state.moveDir);
        assertEquals(0, state.movementVelX);
        assertEquals(new Point(600, 0), state.moveTarget, "the attack pauses rather than cancels the order");
        GCMovement.jumpInPlace(bot);
        GCMovement.jumpToward(bot, 1);
        assertFalse(state.inAir, "a new jump must not start halfway through a grounded attack");
        while (MovementClock.nowMs() < state.attackMovementLockUntilMs) {
            tick();
            assertEquals(attackPosition, bot.getPosition(), "must not slide through the swing");
            assertEquals(0, state.stuckMs, "the animation hold is not a navigation stall");
        }
        tick();
        assertTrue(bot.getPosition().x > attackPosition.x, "walking resumes when the lock expires");
        for (int frame = 0; frame < 200 && arrivals.get() == 0; frame++) tick();
        assertEquals(1, arrivals.get(), "the paused move retains its arrival callback");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void climbingRejectsActiveCombatAndCosmeticAttacks(boolean ladder) throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, -50), Job.BEGINNER);
        BotPhysicsEngine.attachToRope(state, bot, new Rope(0, -200, 0, ladder), -50);
        Monster mob = monster(9300005, 99151, new Point(30, -50));
        inject(map, mob);
        Map<Monster, List<Integer>> hits = Map.of(mob, List.of(100));
        clearInvocations(map);

        assertFalse(BotAttackDriver.botAttack(bot).hit());
        assertFalse(BotAttackDriver.forceSingle(bot).hit());
        assertFalse(BotAttackDriver.forceAoe(bot).hit());
        assertFalse(BotAttackDriver.forceUltimate(bot).hit());
        BotAttack.basicSwing(bot);
        BotAttack.skillSwing(bot, 1001004);
        BotAttack.rangedSwing(bot, 3001004);
        BotAttack.magicSwing(bot, 2001004);
        assertEquals(REJECTED, BotAttackEffects.meleeStrike(bot, hits, 0, 0, 5, 0, 4, (short) 300));
        assertEquals(REJECTED, BotAttackEffects.rangedStrike(bot, hits, 3001004, 1, 0, 22, 0, 4, (short) 300));
        assertEquals(REJECTED, BotAttackEffects.magicStrike(bot, hits, 2001004, 1, 28, 0, 4, (short) 300));
        when(bot.getLevel()).thenReturn(120);
        when(bot.getJob()).thenReturn(Job.FIGHTER);
        BotFinalAttack.maybeTrigger(bot, WeaponType.SWORD1H, 5, 0, hits);
        when(bot.getJob()).thenReturn(Job.CHIEFBANDIT);
        assertNull(BotMesoBomb.tryDetonate(bot, false));
        when(bot.getJob()).thenReturn(Job.HERMIT);
        assertNull(BotShadowMeso.tryThrow(bot, WeaponType.CLAW, false));
        assertNull(BotShadowWeb.tryCast(bot, false));
        when(bot.getJob()).thenReturn(Job.FP_MAGE);
        assertNull(BotGroundMists.tryCast(bot, false));
        assertFalse(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));

        verify(map, never()).broadcastMessage(eq(bot), any(Packet.class), eq(false));
        verify(mob, never()).damage(any(Character.class), anyInt(), anyBoolean());
        assertEquals(0L, state.attackMovementLockUntilMs);
        assertTrue(state.climbing, "rejecting an attack must not dismount the climber");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void passiveEnergyRetaliationDoesNotInterruptWalkingOrClimbing(int pose) throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.MARAUDER);
        when(bot.getLevel()).thenReturn(120);
        when(bot.getEnergyBar()).thenReturn(15_000);
        GCMovement.move(bot, 600, 0);
        if (pose == 0) {
            state.moveDir = 1;
            BotPhysicsEngine.applyGroundMotion(state, bot, BotPhysicsEngine.findGroundFoothold(map, bot.getPosition()));
        } else {
            BotPhysicsEngine.attachToRope(state, bot, new Rope(0, -200, 0, pose == 2), -50);
        }
        Point position = new Point(bot.getPosition());
        int moveDir = state.moveDir;
        int velocity = state.movementVelX;
        double hspeed = state.hspeed;
        Monster mob = monster(9300005, 99153, new Point(30, position.y));
        when(mob.getStati(any())).thenReturn(null);
        clearInvocations(map);

        assertTrue(BotEnergyCharge.claimRetaliationBeat(bot), "a passive retaliation is legal in every pose");
        BotEnergyCharge.strike(bot, mob);

        verify(map).broadcastMessage(eq(bot), any(Packet.class), eq(false));
        verify(mob).damage(eq(bot), intThat(damage -> damage > 0), eq(false));
        assertEquals(position, bot.getPosition());
        assertEquals(pose != 0, state.climbing);
        assertEquals(moveDir, state.moveDir);
        assertEquals(velocity, state.movementVelX);
        assertEquals(hspeed, state.hspeed);
        assertEquals(0L, state.attackMovementLockUntilMs, "a passive retaliation must not arm the active-attack lock");
        assertEquals(new Point(600, 0), state.moveTarget);
    }

    @Test
    void aRopeGrabBetweenAttackPreparationAndBroadcastRejectsTheStrike() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster mob = monster(9300005, 99152, new Point(30, 0));
        Packet packet = mock(Packet.class);
        clearInvocations(map);
        try (var packets = mockStatic(org.gms.util.PacketCreator.class)) {
            packets.when(() -> org.gms.util.PacketCreator.closeRangeAttack(eq(bot), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyMap(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
                BotPhysicsEngine.attachToRope(state, bot, new Rope(0, -200, 0, false), -50);
                return packet;
            });
            assertEquals(REJECTED, BotAttackEffects.meleeStrike(bot, Map.of(mob, List.of(100)),
                    0, 0, 5, 0, 4, (short) 300));
        }
        verify(map, never()).broadcastMessage(eq(bot), any(Packet.class), eq(false));
        verify(mob, never()).damage(any(Character.class), anyInt(), anyBoolean());
        assertTrue(state.climbing);
    }

    @Test
    void aGroundedAttackPublishesItsStopBeforeTheAttackUnderThePhysicsLock() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.BEGINNER);
        state.alertResetScheduled = true;
        state.moveDir = 1;
        state.movementVelX = 140;
        state.hspeed = 7.0;
        ObserverTracker.markObservedNow(map.getId());
        Packet attack = mock(Packet.class);
        clearInvocations(map);
        doAnswer(inv -> {
            assertTrue(Thread.holdsLock(state), "pose check, stop and attack share the physics lock");
            assertEquals(0, state.movementVelX);
            return null;
        }).when(map).broadcastMessage(eq(bot), any(Packet.class), eq(false));

        assertTrue(GCMovement.broadcastActiveAttack(bot, attack, 800));

        var order = inOrder(map);
        order.verify(map).broadcastMessage(eq(bot), argThat(packet -> packet != attack), eq(false));
        order.verify(map).broadcastMessage(bot, attack, false);
    }

    @Test
    void aJumpAttackKeepsItsFlightButCannotGrabARopeDuringTheSwing() throws Exception {
        MapleMap geometry = map(922019997);
        floor(geometry, 1, -100, 0, 800);
        Rope rope = new Rope(0, -200, 100, false);
        geometry.addRope(rope);
        TestGraphBake.ensure(geometry, BotMovementProfile.base());
        MapleMap map = spy(geometry);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        state.alertResetScheduled = true;
        BotPhysicsEngine.beginGroundJump(state, bot, 0);
        state.climbUpIntent = true;
        float takeoffVelocity = state.velY;

        assertTrue(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
        assertTrue(state.inAir);
        assertEquals(takeoffVelocity, state.velY, "attacking must not freeze the jump");
        BotMovementManager.tickAirborne(state, new Point(0, -100));
        assertTrue(state.inAir);
        assertFalse(state.climbing);
        assertTrue(bot.getPosition().y < 0, "gravity and the jump arc continue during an attack");
        MovementClock.advance(800);
        BotMovementManager.tickAirborne(state, new Point(0, -100));
        assertTrue(state.climbing, "rope grabbing resumes after the attack animation");
    }

    @Test
    void recordedMovementClimbingStancesAlsoRejectAttacks() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.BEGINNER);
        states().remove(BOT_ID);
        clearInvocations(map);
        for (int stance : new int[]{CharacterStance.ROPE_STANCE, CharacterStance.LADDER_STANCE}) {
            when(bot.getStance()).thenReturn(stance);
            assertTrue(GCMovement.isClimbing(bot));
            assertFalse(BotAttackDriver.forceSingle(bot).hit());
            assertFalse(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
            BotAttack.skillSwing(bot, 1001004);
        }
        verify(map, never()).broadcastMessage(eq(bot), any(Packet.class), eq(false));
    }

    @Test
    void aKnockbackDuringTheAttackHoldStillRunsItsPhysics() throws Exception {
        MapleMap map = map(922019999);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        state.alertResetScheduled = true;
        assertTrue(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
        BotPhysicsEngine.beginKnockback(state, bot, bot.getPosition(), -10f, 4);
        tick();
        assertTrue(state.inAir);
        assertTrue(bot.getPosition().x > 0, "an attack lock must not pin a real recoil in place");
        assertTrue(bot.getPosition().y < 0);
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"BEGINNER", "HUNTER", "CLERIC"})
    void aRejectedDriverStrikeHasNoHitCooldownOrLandedHitEffects(Job job) throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), job);
        Monster mob = monster(9300005, 99154, new Point(50, 0));
        inject(map, mob);
        Packet packet = mock(Packet.class);
        try (var packets = mockStatic(org.gms.util.PacketCreator.class);
             var demon = mockStatic(BotDemonRider.class);
             var followup = mockStatic(BotFinalAttack.class);
             var mount = mockStatic(soloMapling.ArtificialPlayer.BotMountSystem.BotMount.class);
             var auras = mockStatic(BotAuraState.class)) {
            org.mockito.stubbing.Answer<Packet> grab = inv -> {
                BotPhysicsEngine.attachToRope(state, bot, new Rope(0, -200, 0, false), -50);
                return packet;
            };
            packets.when(() -> org.gms.util.PacketCreator.closeRangeAttack(eq(bot), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyMap(), anyInt(), anyInt(), anyInt())).thenAnswer(grab);
            packets.when(() -> org.gms.util.PacketCreator.rangedAttack(eq(bot), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyInt(), anyMap(), anyInt(), anyInt(), anyInt())).thenAnswer(grab);
            packets.when(() -> org.gms.util.PacketCreator.magicAttack(eq(bot), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyMap(), anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(grab);
            var result = BotAttackDriver.forceSingle(bot);
            assertFalse(result.hit());
            assertEquals(0, result.damage());
            assertEquals(0L, BotAttackDriver.nextAttackEpochMs(BOT_ID));
            demon.verifyNoInteractions();
            followup.verifyNoInteractions();
            mount.verify(() -> soloMapling.ArtificialPlayer.BotMountSystem.BotMount.cancelForAction(bot), never());
            auras.verify(() -> BotAuraState.cancelHidesForAction(bot), never());
            verify(mob, never()).damage(any(Character.class), anyInt(), anyBoolean());
        }
    }

    @Test
    void aRejectedSwingCannotHitTheReactor() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Reactor box = reactor(2202003, 99155, new Point(20, 0));
        inject(map, box);
        try (var packets = mockStatic(org.gms.util.PacketCreator.class);
             var reactors = mockStatic(CustomReactor.class)) {
            packets.when(() -> org.gms.util.PacketCreator.closeRangeAttack(eq(bot), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyMap(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
                BotPhysicsEngine.attachToRope(state, bot, new Rope(0, -200, 0, false), -50);
                return mock(Packet.class);
            });
            assertFalse(PqActions.hitReactorIfInReach(bot, box.getObjectId()));
            reactors.verifyNoInteractions();
        }
    }

    @Test
    void aMageBlinkKeepsTheRemainingAttackLock() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.CLERIC);
        state.alertResetScheduled = true;
        GCMovement.move(bot, 600, 0);
        assertTrue(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
        long deadline = state.attackMovementLockUntilMs;
        assertTrue(GCMovement.teleport(bot, 150, 0));
        assertEquals(deadline, state.attackMovementLockUntilMs);
        Point landing = new Point(bot.getPosition());
        tick();
        assertEquals(landing, bot.getPosition());
    }

    @Test
    void flashJumpCannotStartInsideAnActiveGroundAttack() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.HERMIT);
        state.alertResetScheduled = true;
        assertTrue(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
        assertFalse(GCMovement.flashJump(bot, 600, 1f));
        assertFalse(GCMovement.debugFlashJump(bot, 1, 1f));
        assertFalse(state.inAir);
        MovementClock.advance(800);
        assertTrue(GCMovement.flashJump(bot, 600, 1f));
    }

    @Test
    void recordedMovementAndActiveAttacksShareTheSameLease() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.BEGINNER);
        states().remove(BOT_ID);
        assertTrue(MovementCommands.tryAcquireMovementLock(bot));
        try {
            assertFalse(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
        } finally {
            MovementCommands.releaseMovementLock(bot);
        }
        assertTrue(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
        assertFalse(MovementCommands.tryAcquireMovementLock(bot));
        MovementClock.advance(800);
        assertTrue(MovementCommands.tryAcquireMovementLock(bot));
        MovementCommands.releaseMovementLock(bot);
    }

    @Test
    void disablingDynamicMovementPreservesTheRemainingAttackLease() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.BEGINNER);
        state.alertResetScheduled = true;
        assertTrue(MovementCommands.tryAcquireMovementLock(bot, state));
        assertTrue(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), 800));
        GCMovement.disable(bot);
        assertFalse(GCMovement.isEnabled(bot));
        assertTrue(GCMovement.isAttacking(bot));
        assertFalse(MovementCommands.tryAcquireMovementLock(bot));
        MovementClock.advance(800);
        assertTrue(MovementCommands.tryAcquireMovementLock(bot));
        MovementCommands.releaseMovementLock(bot);
    }

    @Test
    void aLongActionHoldsMovementAndAutomaticAttacksPastTheOldHalfSecond() throws Exception {
        MapleMap map = attackMap();
        createBot(map, new Point(0, 0), Job.BEGINNER);
        state.alertResetScheduled = true;
        int duration = BotAttackData.animationDurationMs(BotAttackData.actionFor(
                org.gms.constants.skills.FPArchMage.METEOR_SHOWER, null));
        assertTrue(duration > 2000, "the test must exercise a long WZ body action");
        GCMovement.move(bot, 600, 0);
        assertTrue(GCMovement.broadcastActiveAttack(bot, mock(Packet.class), duration));
        MovementClock.advance(800);
        tick();
        assertEquals(new Point(0, 0), bot.getPosition());
        assertEquals("attack animation", BotAttackDriver.botAttack(bot).reason());
        MovementClock.advance(duration);
        tick();
        assertTrue(bot.getPosition().x > 0);
    }

    @Test
    void movementHoldUsesBodyFramesPlusTwoHundredMilliseconds() {
        var body = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.CHARACTER)
                .getData("00002000.img");
        for (var action : Map.of(5, "swingO1", 55, "savage", 69, "genesis", 66, "meteor").entrySet()) {
            int duration = 0;
            for (var frame : body.getChildByPath(action.getValue())) {
                duration += Math.abs(org.gms.provider.DataTool.getInt("delay", frame, 100));
            }
            assertEquals(duration + 200, BotAttackData.animationDurationMs(action.getKey()));
            assertTrue(duration > 0);
        }
        assertTrue(BotAttackData.animationDurationMs(66) > BotAttackData.animationDurationMs(5));
    }

    @Test
    void aPitVerdictIsRecheckedWhenMovementCapabilitiesChange() throws Exception {
        MapleMap map = map(922019972);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -120, 100);
        floor(map, 3, -100, -600, 100);
        createBot(map, new Point(0, 0), Job.CLERIC);
        state.moveTarget = new Point(0, -120);
        assertFalse(BotMovementManager.selfHealDeadPit(state), "Teleport is a real exit");
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        assertTrue(BotMovementManager.selfHealDeadPit(state), "the same floor traps a non-blink bot");
    }

    @Test
    void aFallbackTargetKeepsItsDeadlineUntilItIsAbandoned() throws Exception {
        MapleMap map = map(922019973);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -500, 100);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        inject(map, monster(9300005, 99101, new Point(0, -500)));
        PqActions.seekAndAttack(bot);
        assertNotNull(state.moveTarget);
        for (int beat = 0; beat < 17; beat++) {
            MovementClock.advance(250);
            PqActions.seekAndAttack(bot);
        }
        assertNull(state.moveTarget, "the unreachable fallback must time out after four seconds");
        assertFalse(state.moveTargetTracking);
    }

    @Test
    void aForeignLedgeCannotStealAMeleeSwingsFacing() throws Exception {
        MapleMap map = map(922019974);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, -100, -40, -1);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster blocked = monster(9300005, 99102, new Point(-40, -40));
        Monster reachable = monster(9300005, 99103, new Point(70, 0));
        inject(map, blocked); inject(map, reachable);
        assertTrue(BotAttackDriver.canReach(bot, reachable));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.forceSingle(bot).hit(), "face and hit the same-floor target");
            effects.verify(() -> BotAttackEffects.meleeStrike(eq(bot),
                    argThat(hits -> hits.containsKey(reachable) && !hits.containsKey(blocked)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
        }
    }

    @Test
    void autoFacesTheAttackableMobInsteadOfTheCloserOutOfRangeMob() throws Exception {
        MapleMap map = map(922019981);
        floor(map, 1, -100, 0, 500);
        floor(map, 2, -100, -160, -1);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster blocked = monster(9300169, 99113, new Point(-30, -160));
        Monster target = monster(9300170, 99114, new Point(400, 0));
        inject(map, blocked); inject(map, target);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target));
            assertFalse(BotAttackDriver.canReach(bot, blocked));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
            effects.verify(() -> BotAttackEffects.rangedStrike(eq(bot),
                    argThat(hits -> hits.containsKey(target) && !hits.containsKey(blocked)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
            for (int beat = 0; beat < 80; beat++) {
                PqActions.seekAndAttack(bot, true);
                tick();
            }
            assertNull(state.moveTarget, "hold once inside the same approach band as grinding");
            assertTrue(Math.abs(bot.getPosition().x - 400) <= CombatSpacing.approachX(bot));
        }
    }

    @Test
    void autoReachIncludesThePackProfileAndItsTargetCap() throws Exception {
        MapleMap map = map(922019988);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), Job.WARRIOR);
        // Geometry is not the rule under test: this case proves AUTO picks the TALLER pack box
        // (melee 130x80 vs single 90x60) and that the pack cap admits the second body. The
        // projectile routes cannot express that any more - the 1:4 shot arc caps the vertical
        // gap below the single profile's reachY, so range, not the pack box, would decide.
        Monster first = monster(9300005, 99121, new Point(60, -70));
        Monster second = monster(9300005, 99122, new Point(110, -70));
        inject(map, first); inject(map, second);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, first), "AUTO chooses the taller pack box");
            assertTrue(BotAttackDriver.canReach(bot, second));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
            effects.verify(() -> BotAttackEffects.meleeStrike(eq(bot),
                    argThat(hits -> hits.containsKey(first) && hits.containsKey(second)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
        }
    }

    @Test
    void autoReachDoesNotClaimAMobOutsideTheActualTargetCap() throws Exception {
        MapleMap map = map(922019989);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster first = monster(9300005, 99123, new Point(30, 0));
        Monster second = monster(9300005, 99124, new Point(70, 0));
        inject(map, first); inject(map, second);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, first));
            assertFalse(BotAttackDriver.canReach(bot, second));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
            effects.verify(() -> BotAttackEffects.meleeStrike(eq(bot),
                    argThat(hits -> hits.containsKey(first) && !hits.containsKey(second)),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyShort()));
        }
    }

    @Test
    void anAttackBlockingDebuffDoesNotCountAsAWorkingFiringPosition() throws Exception {
        MapleMap map = map(922019992);
        floor(map, 1, -100, 0, 800);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99130, new Point(200, 0));
        inject(map, target);
        var status = mock(soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState.class);
        when(status.blocksAttack()).thenReturn(true);
        try (var statuses = mockStatic(soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState.class)) {
            statuses.when(() -> soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState.of(bot)).thenReturn(status);
            assertFalse(BotAttackDriver.canReach(bot, target));
            assertFalse(BotAttackDriver.botAttack(bot).hit());
        }
    }

    /**
     * The steepest shot that still lands from this room's firing ledge. At 1:4 the arc, not the
     * reach box, is the vertical limit: 450px of horizontal reach allows 112px of lift, so the
     * boundary cases sit there (they used to sit at the box's 150 because 1:2 was looser than it).
     */
    @ParameterizedTest
    @ValueSource(ints = {-112, -110, 111})
    void legalVerticalBoundaryShotsAreNotFilteredOut(int targetY) throws Exception {
        MapleMap map = map(922019982 + targetY + 150);
        floor(map, 1, 0, 0, 300);
        floor(map, 2, 500, targetY, 600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99115, new Point(550, targetY));
        inject(map, target);
        assertFalse(GCMovement.canPathTo(bot, 550, targetY));
        assertTrue(BotAttackDriver.firingPositions(bot, target).contains(new Point(200, 0)));
        PqActions.seekAndAttack(bot, true);
        assertNotNull(state.moveTarget);
        Point firing = new Point(state.moveTarget);
        assertEquals(0, firing.y);
        assertTrue(GCMovement.canPathTo(bot, firing.x, firing.y));
        BotPhysicsEngine.teleportTo(state, bot, firing);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-449, 450})
    void firingBoxAndActualSwingAgreeAtBothHorizontalEdges(int shooterX) throws Exception {
        MapleMap map = map(922019987);
        floor(map, 1, -600, 0, 600);
        createBot(map, new Point(shooterX, 0), Job.HUNTER);
        Monster target = monster(9300170, 99120, new Point(0, 0));
        inject(map, target);
        assertTrue(BotAttackDriver.firingPositions(bot, target).contains(bot.getPosition()));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target));
            assertTrue(BotAttackDriver.botAttack(bot).hit());
        }
    }

    @Test
    void aTargetThatBecomesUnreachableYieldsToAnotherPathableMob() throws Exception {
        MapleMap map = map(922019986);
        floor(map, 1, -100, 0, 800);
        floor(map, 2, 450, -500, 550);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster first = monster(9300005, 99116, new Point(500, 0));
        inject(map, first);
        PqActions.seekAndAttack(bot);
        assertEquals(new Point(500, 0), state.moveTarget);
        when(first.getPosition()).thenReturn(new Point(500, -500));
        inject(map, monster(9300005, 99117, new Point(600, 0)));
        MovementClock.advance(1250);
        PqActions.seekAndAttack(bot);
        assertEquals(new Point(600, 0), state.moveTarget);
    }

    /**
     * A bot that is already in reach keeps the order it is flying to, mid-arc.
     *
     * <p>The retired shape of this check was "some goal must exist after the beat", which only the
     * buggy fall-through satisfied: an airborne in-reach bot re-issued the MOB'S OWN PIXEL as its
     * goal, so every knockback came back as "walk into the mob". The invariant is narrower and it
     * is the one that matters: the landing order survives, and the chase never replaces it with the
     * mob's pixel.
     */
    @Test
    void enteringAttackRangeMidFlightKeepsItsLandingOrder() throws Exception {
        MapleMap map = map(922019991);
        floor(map, 1, -100, 0, 800);
        // In range under the 1:4 arc (dx=100 allows 25px of lift) so the beat is about the
        // landing order, not about whether the shot is legal.
        createBot(map, new Point(100, -20), Job.HUNTER);
        Monster target = monster(9300170, 99129, new Point(200, 0));
        inject(map, target);
        GCMovement.move(bot, 100, 0); // the landing order the arc is flying to
        Point landing = new Point(state.moveTarget);
        state.inAir = true;
        try (var effects = mockStatic(BotAttackEffects.class)) {
            PqActions.seekAndAttack(bot, true);
            assertEquals(landing, state.moveTarget, "an airborne shot keeps its landing order");
            assertNotEquals(target.getPosition(), state.moveTarget,
                    "and must never re-aim at the mob's own pixel");
            assertTrue(state.inAir);
        }
    }

    /** A shot at 400px is legal, but the ordinary fighting position is 80% of 450px, not max range. */
    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER", "CLERIC", "GUNSLINGER"})
    void aProjectileKitClosesToTheSameBandAsGrindingWithoutWalkingOntoTheMob(Job job) throws Exception {
        MapleMap map = map(922019993);
        floor(map, 1, -600, 0, 900);
        createBot(map, new Point(0, 0), job);
        inject(map, monster(9300170, 99140, new Point(400, 0)));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            for (int beat = 0; beat < 200; beat++) {
                PqActions.seekAndAttack(bot);
                tick();
            }
            int dx = Math.abs(400 - bot.getPosition().x);
            assertEquals(360, CombatSpacing.approachX(bot));
            assertTrue(dx <= CombatSpacing.approachX(bot) && dx >= 300,
                    "close to the shared approach band, then hold; dx=" + dx);
            assertNull(state.moveTarget);
        }
    }

    /** Repeated kite steps must finish and use grinding's 150px trigger / 120px re-entry band. */
    @Test
    void aClosingMobCannotLeaveARangedKitInTouchRange() throws Exception {
        MapleMap map = map(922019994);
        floor(map, 1, -3000, 0, 3000);
        createBot(map, new Point(0, 0), Job.HUNTER);
        AtomicReference<Point> mobPos = new AtomicReference<>(new Point(400, 0));
        Monster mob = monster(9300170, 99141, new Point(400, 0));
        when(mob.getPosition()).thenAnswer(inv -> mobPos.get());
        inject(map, mob);
        int tightest = Integer.MAX_VALUE;
        try (var effects = mockStatic(BotAttackEffects.class)) {
            for (int beat = 0; beat < 400; beat++) {
                Point before = bot.getPosition();
                Point chase = mobPos.get();
                // The mob walks at the bot, like a boss chasing its nearest player.
                int step = Math.min(6, Math.abs(chase.x - before.x));
                if (step > 0) {
                    mobPos.set(new Point(chase.x > before.x ? chase.x - step : chase.x + step, chase.y));
                }
                PqActions.seekAndAttack(bot);
                tick();
                if (beat > 20) {
                    tightest = Math.min(tightest, Math.abs(bot.getPosition().x - mobPos.get().x));
                }
            }
        }
        assertTrue(tightest >= 100,
                "a chasing mob must not trap the bot in touch range; tightest=" + tightest
                        + " bot=" + bot.getPosition() + " mob=" + mobPos.get());
    }

    /**
     * Cornered against the ledge edge a ranged kit cannot hold the band - what it must never do is
     * walk TOWARD the mob it is already too close to (the old fall-through did exactly that, aiming
     * at the mob's own pixel). It stands and fires, and the standoff resumes when room returns.
     */
    @Test
    void aCorneredRangedKitStandsAndFiresInsteadOfWalkingIntoTheMob() throws Exception {
        MapleMap map = map(922019996);
        floor(map, 1, -100, 0, 300);
        createBot(map, new Point(250, 0), Job.HUNTER);
        inject(map, monster(9300170, 99143, new Point(200, 0)));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            PqActions.seekAndAttack(bot);
            assertTrue(state.moveTarget == null
                            || state.moveTarget.x >= 250,
                    "a cornered kit never walks toward the mob; goal=" + state.moveTarget);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER", "CLERIC"})
    void onlyTheOrdinaryRangedMovementStyleKitesBack(Job job) throws Exception {
        MapleMap map = map(922019993);
        floor(map, 1, -600, 0, 900);
        createBot(map, new Point(0, 0), job);
        Monster target = monster(9300170, 99210, new Point(100, 0));
        inject(map, target);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            PqActions.seekAndAttack(bot);
            if (job == Job.HUNTER) {
                assertEquals(new Point(-90, 0), state.moveTarget,
                        "the first kite is a 90px step, not a forced 225px gap");
                MovementClock.advance(250);
                PqActions.seekAndAttack(bot);
                assertEquals(new Point(-90, 0), state.moveTarget, "finish the step across cooldown beats");
            } else {
                assertNull(state.moveTarget, "mages retain the same non-kiting movement style as grinding");
            }
        }
    }

    @Test
    void aLegalCrossLedgeShotKeepsItsPlatformOutsideTheOrdinaryApproachBand() throws Exception {
        MapleMap map = map(922019993);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, 350, -40, 450);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99211, new Point(400, -40));
        inject(map, target);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target));
            PqActions.seekAndAttack(bot, true);
            assertNull(state.moveTarget, "do not replace an honest ledge shot with an impossible approach");
        }
    }

    @Test
    void anOrdinaryReachableLedgeDoesNotRestoreMaxRangeStanding() throws Exception {
        MapleMap map = map(922019993);
        floor(map, 1, -100, 0, 160);
        floor(map, 2, 190, 0, 500);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99212, new Point(400, 0));
        inject(map, target);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.canReach(bot, target), "the shot is within 450px");
            assertTrue(GCMovement.canPathTo(bot, 400, 0), "the ordinary shelf is reachable");
            PqActions.seekAndAttack(bot);
            assertNotNull(state.moveTarget, "normal reachable shelves still use the shared approach band");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 200, 350, 440, 520})
    void stage1CombatBeatWalksToAndCollectsARangedPassBeforeChasingAgain(int distance) throws Exception {
        // Still stage 1, but not its real map id: synthetic geometry must not poison later WZ sims.
        MapleMap map = map(LudiPqData.ENTRY_MAP + 99);
        floor(map, 1, -600, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        Point passAt = new Point(distance, 0);
        MapItem pass = pass(99200, passAt);
        inject(map, pass);
        inject(map, monster(9300005, 99201, new Point(1200, 0)));

        try (var effects = mockStatic(BotAttackEffects.class); var loot = mockStatic(DropCommands.class, CALLS_REAL_METHODS)) {
            loot.when(() -> DropCommands.botLootSingleDrop(bot, pass)).thenAnswer(inv -> {
                assertTrue(bot.getPosition().distanceSq(passAt) <= 60.0 * 60.0,
                        "a distant pass must be walked to, never vacuumed");
                pass.setPickedUp(true);
                map.removeMapObject(pass);
                return null;
            });
            pq.grindTick(); // no macro tick: the 4Hz beat alone must discover the drop
            assertEquals(passAt, state.moveTarget);
            long progressAt = state.moveProgressAtMs;
            MovementClock.advance(250);
            pq.grindTick();
            assertEquals(progressAt, state.moveProgressAtMs, "repeated beats must not restart the live walk");
            loot.verify(() -> DropCommands.botLootSingleDrop(bot, pass), never());

            for (int tick = 0; tick < 200 && !pass.isPickedUp(); tick++) {
                pq.grindTick();
                tick();
            }
            assertTrue(pass.isPickedUp(), "the combat beat must finish pickup without a macro tick");
            loot.verify(() -> DropCommands.botLootSingleDrop(bot, pass));
            pq.grindTick();
            assertEquals(new Point(1200, 0), state.moveTarget, "resume hunting once the pass is collected");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stage1RangedKillStartsPickupOnTheNextCombatBeat(boolean lastMob) throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 99);
        floor(map, 1, -600, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        Monster first = monster(9300005, 99202, new Point(350, 0));
        inject(map, first);
        if (!lastMob) inject(map, monster(9300005, 99203, new Point(1200, 0)));
        MapItem drop = pass(99204, new Point(350, 0));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            effects.when(() -> BotAttackEffects.rangedStrike(eq(bot), anyMap(), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyInt(), anyInt(), anyShort())).thenAnswer(inv -> {
                when(first.isAlive()).thenReturn(false);
                inject(map, drop);
                return KILLED;
            });
            pq.grindTick();
            MovementClock.advance(250);
            pq.grindTick();
            assertEquals(drop.getPosition(), state.moveTarget,
                    "the next 250ms beat must reclaim the driver from the next-mob chase");
        }
    }

    @Test
    void combatPickupSkipsProtectedStacksAndUnreachablePasses() throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 99);
        floor(map, 1, -100, 0, 200);
        floor(map, 2, 350, -150, 450);
        createBot(map, new Point(0, 0), Job.HUNTER);
        MapItem stack = pass(99205, new Point(100, 0));
        stack.getItem().setQuantity((short) 2);
        inject(map, stack);
        MapItem addressed = pass(99206, new Point(110, 0));
        addressed.lockItem();
        try {
            addressed.setPermanentOwnerLocked(true);
        } finally {
            addressed.unlockItem();
        }
        inject(map, addressed);
        inject(map, pass(99207, new Point(400, -150)));
        assertFalse(GCMovement.canPathTo(bot, 400, -150));
        combatBot().grindTick();
        assertNull(state.moveTarget, "mechanic stacks, addressed piles and unreachable drops cannot own steering");
    }

    @Test
    void aCombatPickupDoesNotReplaceAStageWalkOrReactorBeat() throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 99);
        floor(map, 1, -600, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        inject(map, pass(99208, new Point(350, 0)));
        Point door = new Point(-300, 0);
        PqActions.walkTo(bot, door);
        pq.grindTick();
        assertEquals(door, state.moveTarget, "the portal/puzzle walk keeps steering");
        PqActions.clearSeekState(BOT_ID);
        Reactor box = reactor(2201000, 99209, new Point(1000, 0));
        inject(map, box);
        PqActions.armReactorBeat(bot, box.getObjectId());
        pq.grindTick();
        assertNotEquals(new Point(350, 0), state.moveTarget, "a reactor beat outranks opportunistic pickup");
        PqActions.clearReactorBeat(BOT_ID);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4, 7, 9})
    void macroAndCombatBeatsKeepTheSamePickupGoal(int stage) throws Exception {
        int mapId = stage == 4 ? LudiPqData.STAGE4_ROOM_FIRST
                : stage == 9 ? LudiPqData.STAGE_9 - 1 : LudiPqData.ENTRY_MAP + (stage - 1) * 100 + 99;
        MapleMap map = map(mapId);
        floor(map, 1, -1000, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        MapItem drop = pass(99300, new Point(-520, 0));
        inject(map, drop);
        inject(map, monster(9300005, 99301, new Point(1000, 0)));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            pq.grindTick();
            assertEquals(drop.getPosition(), state.moveTarget);
            // A nearer pass appearing must not pull an already committed pickup back and forth.
            inject(map, pass(99302, new Point(200, 0)));
            long progressAt = state.moveProgressAtMs;
            for (int beat = 0; beat < 8; beat++) {
                MovementClock.advance(250);
                switch (stage) {
                    case 3 -> LudiStages.breakCratesAndHunt(bot);
                    case 4 -> LudiStages.workDoorRooms(bot, mapId, mapId);
                    case 9 -> LudiStages.fightBoss(bot);
                    default -> LudiStages.gatherPasses(bot, stage);
                }
                pq.grindTick();
                assertEquals(drop.getPosition(), state.moveTarget, "no macro/combat target ping-pong");
                assertEquals(progressAt, state.moveProgressAtMs, "do not restart the movement watchdog");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDisappearedPassCannotLeaveTheBotPermanentlyWaiting(boolean liveMob) throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 98);
        floor(map, 1, -1000, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        MapItem drop = pass(99303, new Point(-350, 0));
        inject(map, drop);
        if (liveMob) inject(map, monster(9300005, 99304, new Point(1000, 0)));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            pq.grindTick();
            drop.setPickedUp(true);
            map.removeMapObject(drop);
            for (int beat = 0; beat < 60 && (state.moveTarget != null || beat == 0); beat++) {
                pq.grindTick();
                if (liveMob) {
                    assertEquals(new Point(1000, 0), state.moveTarget, "the next chase can replace the vanished drop");
                    return;
                }
                for (int tick = 0; tick < 5; tick++) tick();
            }
            assertNull(state.moveTarget, "continuing to the old pickup point must finish normally");
        }
    }

    @Test
    void aRejectedPickupIsNotChasedAgainByEitherLoop() throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 98);
        floor(map, 1, -1000, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        MapItem drop = pass(99305, new Point(0, 0));
        inject(map, drop);
        inject(map, monster(9300005, 99306, new Point(1000, 0)));
        try (var effects = mockStatic(BotAttackEffects.class);
             var loot = mockStatic(DropCommands.class, CALLS_REAL_METHODS)) {
            loot.when(() -> DropCommands.botLootSingleDrop(bot, drop)).thenAnswer(inv -> null);
            for (int beat = 0; beat < 80; beat++) {
                pq.grindTick();
                if (beat % 8 == 0) LudiStages.gatherPasses(bot, 1);
                assertNotEquals(drop.getPosition(), state.moveTarget, "a refused pickup must not pull the bot back");
                for (int tick = 0; tick < 5; tick++) tick();
            }
            assertTrue(bot.getPosition().x > 300, "continue fighting instead of oscillating over the failed drop");
            loot.verify(() -> DropCommands.botLootSingleDrop(bot, drop));
        }
    }

    @Test
    void anAbandonedPickupWalkDoesNotRestartForever() throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 98);
        floor(map, 1, -1000, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        MapItem drop = pass(99307, new Point(-350, 0));
        inject(map, drop);
        inject(map, monster(9300005, 99308, new Point(1000, 0)));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            pq.grindTick();
            assertEquals(drop.getPosition(), state.moveTarget);
            GCMovement.abandonMove(state); // the driver's actual no-progress/failure notification
            state.moveTarget = null;
            for (int beat = 0; beat < 8; beat++) {
                pq.grindTick();
                LudiStages.gatherPasses(bot, 1);
                assertEquals(new Point(1000, 0), state.moveTarget);
            }
        }
    }

    @Test
    void aFreshDropIsNotMistakenForARejectedPickup() throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 98);
        floor(map, 1, -1000, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        MapItem drop = pass(99309, new Point(0, 0));
        drop.setDropTime(System.currentTimeMillis() + 1000);
        inject(map, drop);
        try (var loot = mockStatic(DropCommands.class, CALLS_REAL_METHODS)) {
            loot.when(() -> DropCommands.botLootSingleDrop(bot, drop)).thenAnswer(inv -> {
                drop.setPickedUp(true);
                map.removeMapObject(drop);
                return null;
            });
            PqActions.lootSteppedOn(bot, new int[]{LudiPqData.PASS});
            loot.verify(() -> DropCommands.botLootSingleDrop(bot, drop), never());
            drop.setDropTime(System.currentTimeMillis() - 1000);
            MovementClock.advance(250);
            PqActions.lootSteppedOn(bot, new int[]{LudiPqData.PASS});
            assertTrue(drop.isPickedUp(), "the 400ms host settle gate must remain retryable");
        }
    }

    @Test
    void failedPickupStateIsScopedToTheMapInstanceAndBotLifetime() throws Exception {
        MapleMap first = map(LudiPqData.ENTRY_MAP + 98);
        floor(first, 1, -1000, 0, 1600);
        createBot(first, new Point(0, 0), Job.HUNTER);
        MapItem drop = pass(99310, new Point(0, 0));
        inject(first, drop);
        try (var loot = mockStatic(DropCommands.class, CALLS_REAL_METHODS)) {
            loot.when(() -> DropCommands.botLootSingleDrop(bot, drop)).thenAnswer(inv -> null);
            PqActions.lootSteppedOn(bot, new int[]{LudiPqData.PASS});
            MovementClock.advance(250);
            PqActions.lootSteppedOn(bot, new int[]{LudiPqData.PASS});
            loot.verify(() -> DropCommands.botLootSingleDrop(bot, drop));

            MapleMap next = map(first.getId()); // a different instance, even with the same map id
            floor(next, 1, -1000, 0, 1600);
            when(bot.getMap()).thenReturn(next);
            inject(next, drop);
            MovementClock.advance(250);
            PqActions.lootSteppedOn(bot, new int[]{LudiPqData.PASS});
            loot.verify(() -> DropCommands.botLootSingleDrop(bot, drop), times(2));

            PqActions.clearSeekState(BOT_ID);
            PqActions.lootSteppedOn(bot, new int[]{LudiPqData.PASS});
            loot.verify(() -> DropCommands.botLootSingleDrop(bot, drop), times(3));
        }
    }

    @Test
    void concurrentMacroAndCombatLoopsCannotReplaceTheCommittedPickup() throws Exception {
        MapleMap map = map(LudiPqData.ENTRY_MAP + 98);
        floor(map, 1, -1000, 0, 1600);
        createBot(map, new Point(0, 0), Job.HUNTER);
        LudiPQBot pq = combatBot();
        MapItem drop = pass(99311, new Point(-520, 0));
        inject(map, drop);
        inject(map, monster(9300005, 99312, new Point(1000, 0)));
        pq.grindTick();
        long progressAt = state.moveProgressAtMs;
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var combat = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int beat = 0; beat < 100; beat++) pq.grindTick();
                return null;
            });
            var macro = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int beat = 0; beat < 100; beat++) LudiStages.gatherPasses(bot, 1);
                return null;
            });
            start.countDown();
            combat.get(10, TimeUnit.SECONDS);
            macro.get(10, TimeUnit.SECONDS);
        }
        assertEquals(drop.getPosition(), state.moveTarget);
        assertEquals(progressAt, state.moveProgressAtMs);
    }

    private LudiPQBot combatBot() {
        when(bot.getParty()).thenReturn(mock(org.gms.net.server.world.Party.class));
        LudiPQBot pq = mock(LudiPQBot.class, CALLS_REAL_METHODS);
        doReturn(bot).when(pq).getChr();
        doReturn(true).when(pq).getRunning();
        doReturn(mock(BotDeath.class)).when(pq).death();
        doReturn(mock(BotDebuffState.class)).when(pq).status();
        return pq;
    }

    private MapItem pass(int oid, Point at) {
        MapItem drop = new MapItem(new Item(LudiPqData.PASS, (short) 1, (short) 1), at,
                null, bot, null, (byte) 2, false);
        drop.setObjectId(oid);
        drop.setDropTime(System.currentTimeMillis() - 5000L);
        return drop;
    }

    /** A melee kit still closes - the standoff is a projectile rule, not a retreat for everyone. */
    @Test
    void aMeleeKitStillClosesToItsOwnReach() throws Exception {
        MapleMap map = map(922019995);
        floor(map, 1, -600, 0, 900);
        createBot(map, new Point(0, 0), Job.BEGINNER);
        Monster mob = monster(9300005, 99142, new Point(400, 0));
        inject(map, mob);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            for (int beat = 0; beat < 600; beat++) {
                PqActions.seekAndAttack(bot);
                tick();
            }
        }
        int dx = Math.abs(bot.getPosition().x - 400);
        assertTrue(dx <= BotAttackDriver.attackReachX(bot),
                "a melee kit must close to its own reach; dx=" + dx);
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER", "CLERIC"})
    void projectilesStillFaceAndHitAForeignLedge(Job job) throws Exception {
        MapleMap map = map(job == Job.HUNTER ? 922019977 : 922019978);
        floor(map, 1, -100, 0, 100);
        floor(map, 2, 200, -40, 300);
        createBot(map, new Point(0, 0), job);
        Monster target = monster(9300005, 99111, new Point(250, -40));
        inject(map, target);
        assertTrue(BotAttackDriver.canReach(bot, target));
        try (var effects = mockStatic(BotAttackEffects.class)) {
            assertTrue(BotAttackDriver.forceSingle(bot).hit(), "route-aware facing preserves cross-ledge shots");
        }
    }

    // =========================================================================
    // Stage 9 - the boss
    // =========================================================================

    /**
     * A ranged kit must not fight Alishar from inside his touch box.
     *
     * <p>Two defects stacked here. The chase only stopped on {@code !canReach}, and the reach box
     * is symmetric around the bot - so "in reach" held at distance ZERO and the bot kept walking
     * onto the boss. And when the boss walked at the bot, nothing stepped back: a bowman stood
     * still and fired while Alishar closed in. Measured before the fix: the bot ended at dx=1
     * (touching) and, when the boss moved, at dx=0.
     */
    @Test
    void stage9ARangedKitNeverFightsAlisharFromInsideHisTouchBox() throws Exception {
        MapleMap map = realMap(922010900);
        assumeTrue(map != null, "GMS083 Map.wz required");
        createBot(map, new Point(-171, 178), Job.HUNTER);
        AtomicReference<Point> bossPos = new AtomicReference<>(new Point(941, 184));
        Monster boss = monster(9300012, 998120, new Point(941, 184));
        when(boss.getPosition()).thenAnswer(inv -> bossPos.get());
        when(boss.isBoss()).thenReturn(true);
        inject(map, boss);
        int tightest = Integer.MAX_VALUE;
        try (var effects = mockStatic(BotAttackEffects.class)) {
            for (int beat = 0; beat < 500; beat++) {
                Point before = bot.getPosition();
                Point chase = bossPos.get();
                // Alishar walks at the bot the way a live boss chases its nearest player.
                int step = Math.min(2, Math.abs(chase.x - before.x));
                if (beat > 60 && step > 0) {
                    bossPos.set(new Point(chase.x > before.x ? chase.x - step : chase.x + step, chase.y));
                }
                LudiStages.applyChasePolicy(bot);
                PqActions.seekAndAttack(bot);
                tick();
                if (beat > 60) {
                    tightest = Math.min(tightest, Math.abs(bot.getPosition().x - bossPos.get().x));
                }
            }
        }
        assertTrue(tightest >= 110,
                "Alishar must never be allowed inside the bowman's standoff; tightest=" + tightest
                        + " bot=" + bot.getPosition() + " boss=" + bossPos.get());
    }

    /** The altar summon stays best-effort and never blocks the fight (unchanged contract). */
    @Test
    void anUnreachableCrystalNeverArmsAWalkAndCannotBlockTheBoss() throws Exception {
        MapleMap map = map(922019975);
        floor(map, 1, -400, 184, 1200);
        floor(map, 2, 550, 0, 650);
        createBot(map, new Point(-171, 184), Job.BEGINNER);
        Reactor crystal = reactor(2201003, 99104, new Point(599, 0));
        inject(map, crystal);
        assertFalse(GCMovement.canPathTo(bot, 599, 0));
        LudiStages.fightBoss(bot);
        assertNull(state.moveTarget, "summoning is best-effort, never an unreachable approach");
        assertFalse(PqActions.movementShielded(BOT_ID));

        when(crystal.getReactorType()).thenReturn(-1);
        Monster boss = monster(9300012, 99105, new Point(700, 184));
        when(boss.isBoss()).thenReturn(true);
        inject(map, boss);
        LudiStages.fightBoss(bot);
        assertEquals(new Point(700, 184), state.moveTarget, "immediately chase the summoned Boss");
    }

    @Test
    void aCrystalInHonestReachIsStillStruck() throws Exception {
        MapleMap map = map(922019976);
        floor(map, 1, 500, 0, 700);
        createBot(map, new Point(590, 0), Job.BEGINNER);
        inject(map, reactor(2201003, 99106, new Point(599, 0)));
        try (var swings = mockStatic(BotAttack.class); var reactors = mockStatic(CustomReactor.class)) {
            swings.when(() -> BotAttack.basicSwing(bot)).thenReturn(true);
            LudiStages.fightBoss(bot);
            reactors.verify(() -> CustomReactor.hitReactorWithScript(map, 99106, bot));
            swings.verify(() -> BotAttack.basicSwing(bot));
        }
        assertNull(state.moveTarget);
        assertFalse(PqActions.movementShielded(BOT_ID));
    }

    @Test
    void aDistantCrystalDoesNotOverwriteAnotherProducersWalk() throws Exception {
        MapleMap map = map(922019979);
        floor(map, 1, -400, 184, 1200);
        floor(map, 2, 550, 0, 650);
        createBot(map, new Point(-171, 184), Job.BEGINNER);
        inject(map, reactor(2201003, 99112, new Point(599, 0)));
        Point pickup = new Point(0, 184);
        GCMovement.move(bot, pickup.x, pickup.y);
        LudiStages.fightBoss(bot);
        assertEquals(pickup, state.moveTarget, "a failed summon attempt owns no movement order");
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER"})
    void stage7RangedBotWalksToAFiringPositionAndKeepsItOnCombatBeats(Job job) throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), job);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        try (var effects = mockStatic(BotAttackEffects.class)) {
            int[][] targets = {{9300170, 224, -1044}, {9300169, 219, -1276}, {9300171, 228, -1543}};
            Monster[] mobs = new Monster[targets.length];
            for (int i = 0; i < targets.length; i++) {
                mobs[i] = monster(targets[i][0], 99107 + i, new Point(targets[i][1], targets[i][2]));
                inject(map, mobs[i]);
            }
            for (int i = 0; i < targets.length; i++) {
                int[] row = targets[i];
                Monster target = mobs[i];
                assertFalse(GCMovement.canPathTo(bot, row[1], row[2]));
                LudiStages.gatherPasses(bot, 7);
                if (!BotAttackDriver.canReach(bot, target)) {
                    assertNotNull(state.moveTarget, "an inaccessible mob floor is not an inaccessible shot");
                    Point firing = new Point(state.moveTarget);
                    assertTrue(GCMovement.canPathTo(bot, firing.x, firing.y));
                    assertNotEquals(target.getPosition(), firing);
                }
                for (int tick = 0; tick < 1200 && !BotAttackDriver.canReach(bot, target); tick++) {
                    if (tick % 5 == 0) PqActions.seekAndAttack(bot);
                    tick();
                }
                assertTrue(BotAttackDriver.canReach(bot, target),
                        "reach the firing ledge for mob " + row[0] + "; ended at " + bot.getPosition());
                assertTrue(BotAttackDriver.forceSingle(bot).hit(), "the selected shot must actually hit");
                PqActions.seekAndAttack(bot);
                assertNull(state.moveTarget, "hold the shot instead of walking onto the mob's ledge");
                when(target.isAlive()).thenReturn(false);
            }
        }
    }

    @Test
    void stage7MeleeBotDoesNotWalkAtAnImpossibleShot() throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), Job.BEGINNER);
        inject(map, monster(9300170, 99110, new Point(224, -1044)));
        LudiStages.gatherPasses(bot, 7);
        assertNull(state.moveTarget);
        for (int beat = 0; beat < 20; beat++) {
            MovementClock.advance(250);
            PqActions.seekAndAttack(bot);
            assertNull(state.moveTarget, "the sticky restriction also governs the combat beat");
        }
    }

    /**
     * A cast is not the "ranged personnel" the frame asks for. Stage 7's shelves stay out of a
     * mage's reach: it is never offered a firing ledge, so it never snipes the three Ratz from
     * across the room. (Its own in-range AoE is untouched - only the remote role is refused.)
     */
    @Test
    void aMageIsNeverGivenAStage7FiringPosition() throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), Job.CLERIC);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        Monster ratz = monster(9300170, 99150, new Point(224, -1044));
        inject(map, ratz);
        assertNull(BotAttackDriver.firingPositions(bot, ratz),
                "a mage gets no remote firing ledge");
        LudiStages.gatherPasses(bot, 7);
        assertNull(state.moveTarget,
                "with no firing ledge there is no shot to walk to, so the mage stays put");
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"HUNTER"})
    void actualAutoBeatsKillAllThreeStage7Targets(Job job) throws Exception {
        MapleMap map = realMap(922010700);
        createBot(map, new Point(-43, -783), job);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        Monster[] mobs = {monster(9300170, 99125, new Point(224, -1044)),
                monster(9300169, 99126, new Point(219, -1276)), monster(9300171, 99127, new Point(228, -1543))};
        for (Monster mob : mobs) inject(map, mob);
        AtomicInteger kills = new AtomicInteger();
        org.mockito.stubbing.Answer<BotAttackEffects.StrikeResult> landHits = inv -> {
            Map<Monster, ?> hits = inv.getArgument(1);
            for (Monster mob : hits.keySet()) {
                if (mob.isAlive()) kills.incrementAndGet();
                doReturn(false).when(mob).isAlive();
            }
            return KILLED;
        };
        try (var effects = mockStatic(BotAttackEffects.class)) {
            effects.when(() -> BotAttackEffects.rangedStrike(eq(bot), anyMap(), anyInt(), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyInt(), anyShort())).thenAnswer(landHits);
            effects.when(() -> BotAttackEffects.magicStrike(eq(bot), anyMap(), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyInt(), anyShort())).thenAnswer(landHits);
            for (int t = 0; t < 1200 && kills.get() < mobs.length; t++) {
                if (t % 5 == 0) {
                    BotAttackDriver.clearBot(BOT_ID); // advance attack cadence without real sleeps
                    if (t == 0) LudiStages.gatherPasses(bot, 7);
                    else PqActions.seekAndAttack(bot);
                }
                tick();
            }
        }
        assertEquals(3, kills.get(), "only actual AUTO target lists retire the mobs, never forceSingle");
    }

    @Test
    void firingSearchResolvesCombatAndMovementOnceInsteadOfPerCandidate() throws Exception {
        MapleMap map = map(922019990);
        floor(map, 1, -100, 0, 100);
        for (int i = 0; i < 12; i++) floor(map, 2 + i, 200 + i * 10, -50, 205 + i * 10);
        createBot(map, new Point(0, 0), Job.HUNTER);
        Monster target = monster(9300170, 99128, new Point(500, -100));
        try (var config = mockStatic(soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig.class, CALLS_REAL_METHODS);
             var profiles = mockStatic(BotMovementProfile.class, CALLS_REAL_METHODS)) {
            Method search = PqActions.class.getDeclaredMethod("findFiringPosition", Character.class, Monster.class);
            search.setAccessible(true);
            assertNotNull(search.invoke(null, bot, target));
            config.verify(() -> soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig.resolve(any(), any(), anyInt()), times(1));
            profiles.verify(() -> BotMovementProfile.fromCharacter(bot), times(1));
        }
    }

    @Test
    void reachableGroundUsesTheRealSlopeAndVerticalBox() throws Exception {
        MapleMap map = map(922019993);
        map.getFootholds().insert(new Foothold(new Point(0, 0), new Point(300, -150), 1));
        createBot(map, new Point(0, 0), Job.HUNTER);
        var bounds = new java.awt.Rectangle(100, -145, 150, 35);
        Point shot = GCMovement.nearestReachableGround(bot, bounds);
        assertNotNull(shot);
        assertTrue(bounds.contains(shot));
        assertEquals(-shot.x / 2, shot.y, 1);
        assertTrue(GCMovement.canPathTo(bot, shot.x, shot.y));
    }

    @ParameterizedTest
    @EnumSource(value = Job.class, names = {"THIEF", "BRAWLER"})
    void theCrossingPolicyOwnsAndRetiresOnlyItsOwnHide(Job job) throws Exception {
        MapleMap map = map(922010506);
        floor(map, 1, -200, 0, 200);
        floor(map, 2, -200, -630, 200);
        createBot(map, new Point(0, 0), job);
        inject(map, monster(9300013, 99131, new Point(0, 0)));
        try (var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(eq(bot), anyInt())).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, inv.getArgument(1));
                return 120000;
            });
            tick();
            assertTrue(BotAuraState.isMonsterImmune(bot));
            BotPhysicsEngine.teleportTo(state, bot, new Point(0, -630));
            tick();
            assertFalse(BotAuraState.isMonsterImmune(bot));
            BotAuraState.onAuraShown(bot, job == Job.THIEF ? 4001003 : 5101007);
            tick();
            assertTrue(BotAuraState.isMonsterImmune(bot), "a separate hide was not granted by the crossing policy");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2000, 6000})
    void guardHideTracksPhysicsRatherThanTheMacroBeat(int macroMs) throws Exception {
        MapleMap map = realMap(922010506);
        createBot(map, new Point(179, -223), Job.THIEF);
        state.movementProfile = BotMovementProfile.fromCharacter(bot);
        BotNavigationGraphProvider.getGraph(map, state.movementProfile);
        inject(map, reactor(2202003, 99108, new Point(-83, -630)));
        Monster guard = monster(9300013, 99109, new Point(62, -443));
        inject(map, guard);
        AtomicInteger contacts = new AtomicInteger();
        Method touching = BotContactDamage.class.getDeclaredMethod("isMobTouchingBot",
                BotMovementState.class, Character.class, Monster.class);
        touching.setAccessible(true);
        var skillData = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.SKILL)
                .getData("400.img").getChildByPath("skill/4001003");
        Method loadSkill = org.gms.client.SkillFactory.class.getDeclaredMethod("loadFromData",
                int.class, org.gms.provider.Data.class);
        loadSkill.setAccessible(true);
        var darkSight = (org.gms.client.Skill) loadSkill.invoke(null, 4001003, skillData);
        try (var damage = mockStatic(BotContactDamage.class, CALLS_REAL_METHODS);
             var skills = mockStatic(org.gms.client.SkillFactory.class, CALLS_REAL_METHODS)) {
            skills.when(() -> org.gms.client.SkillFactory.getSkill(4001003)).thenReturn(darkSight);
            damage.when(() -> BotContactDamage.tickMobDamage(state, bot)).thenAnswer(inv -> {
                if ((boolean) touching.invoke(null, state, bot, guard)) {
                    contacts.incrementAndGet();
                    assertTrue(BotAuraState.isMonsterImmune(bot),
                            "hide must be up BEFORE the touch check at " + bot.getPosition());
                }
                state.lastMobTouchCheckPos = new Point(bot.getPosition());
                state.lastMobTouchMapId = bot.getMapId();
                return null;
            });
            for (int tick = 0; tick < 120; tick++) {
                if (tick * 50 % macroMs == 0) LudiStages.sneakDoorRooms(bot);
                tick();
            }
        }
        assertTrue(contacts.get() > 0, "the simulation must actually cross the guard's body");
        BotPhysicsEngine.teleportTo(state, bot, new Point(-83, -630));
        tick();
        assertFalse(BotAuraState.isMonsterImmune(bot), "the safe box ledge is not a permanent hide");
    }

    /**
     * LPQ stage 4's door rooms are 1866px wide, and the room a bot warps into is NOT always its
     * right-hand spawn ledge: {@code 922010400}'s {@code in01/in02/in05} target the room's
     * {@code st00} (x≈295, right side) while {@code in03/in04} target {@code out00} (x≈-1366, the
     * LEFT exit). The room's only mob stands on the left shelf, so the two spawn-ledge landings
     * sat 914/917px away while the left-exit landings sat 697/726px away. The chase used to filter
     * candidates by {@code |dx| <= 900}, which read exactly those two rooms as "quiet" while
     * {@code LudiStages.workDoorRooms}' whole-map {@code killableMobPresent} still reported work
     * left - the bot neither fought nor left, and only a real player entering the room ever moved
     * it (the host hands mob control to real clients only, so the mob sat still until someone
     * walked in and dragged it into range).
     */
    @ParameterizedTest(name = "room {0}: st00 landing({1},{2}) -> mob({3},{4})")
    @CsvSource({
            "922010401, 295, -79, -619, 225",  // dx 914 - 14px past the old cap
            "922010405, 307, -79, -610, 225"   // dx 917
    })
    void aStage4DoorRoomMobBeyondTheOldHorizontalCapIsStillHunted(int mapId, int entryX, int entryY,
                                                                  int mobX, int mobY) throws Exception {
        MapleMap map = realMap(mapId);
        createBot(map, new Point(entryX, entryY), Job.BEGINNER);
        Monster mob = monster(9300008, 99140 + mapId % 10, new Point(mobX, mobY));
        inject(map, mob);
        assertTrue(Math.abs(mobX - entryX) > 900, "this room only exercises the bug past the old cap");

        PqActions.seekAndAttack(bot);
        assertNotNull(state.moveTarget, "the room's only mob must be chased, not read as quiet");
        assertEquals(mobX, state.moveTarget.x, "the chase must aim at the mob's own floor pixel");
        assertTrue(GCMovement.canPathTo(bot, mobX, mobY), "the mob's shelf must be reachable");

        // End to end: production driver ticks must actually close the gap into attack reach.
        boolean reached = false;
        for (int beat = 0; beat < 400 && !reached; beat++) { // 100s at 250ms/beat
            PqActions.seekAndAttack(bot);
            reached = BotAttackDriver.canReach(bot, mob);
            if (!reached) {
                for (int t = 0; t < 5; t++) tick(); // 250ms of 50ms ticks
            }
        }
        assertTrue(reached, "the bot must walk from the door into the mob's attack reach; ended at "
                + bot.getPosition());
    }

    @Test
    void aStage4LeftExitLandingStillWorks() throws Exception {
        // in03 targets out00 (-1366), 726px from the mob - inside the old cap, so it never stalled.
        MapleMap map = realMap(922010403);
        createBot(map, new Point(-1366, -81), Job.BEGINNER);
        inject(map, monster(9300008, 99145, new Point(-640, 225)));
        PqActions.seekAndAttack(bot);
        assertNotNull(state.moveTarget, "the in-range left-exit landing must keep working");
        assertEquals(-640, state.moveTarget.x);
    }

    @Test
    void aWarpOutOfTheGuardRoomRetiresItsHide() throws Exception {
        MapleMap guardRoom = map(922010506);
        floor(guardRoom, 1, -100, 0, 100);
        createBot(guardRoom, new Point(0, 0), Job.THIEF);
        inject(guardRoom, monster(9300013, 99118, new Point(0, 0)));
        try (var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(bot, 4001003)).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, 4001003);
                return 120000;
            });
            tick();
        }
        assertTrue(BotAuraState.isMonsterImmune(bot));
        MapleMap next = map(922019980);
        floor(next, 1, -100, 0, 100);
        when(bot.getMap()).thenReturn(next); when(bot.getMapId()).thenReturn(next.getId());
        tick();
        assertFalse(BotAuraState.isMonsterImmune(bot));
    }

    @Test
    void aStaleMacroPositionCannotCancelThePhysicsGuardsHide() throws Exception {
        MapleMap map = map(922010506);
        floor(map, 1, -200, -630, 200);
        floor(map, 2, -200, 0, 200);
        createBot(map, new Point(0, -630), Job.THIEF);
        inject(map, monster(9300013, 99119, new Point(0, -443)));
        AtomicReference<Point> position = new AtomicReference<>(new Point(0, -630));
        CountDownLatch sampled = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(bot.getPosition()).thenAnswer(inv -> {
            Point snapshot = position.get();
            if (Thread.currentThread().getName().equals("lpq-macro-review")) {
                sampled.countDown();
                assertTrue(resume.await(5, TimeUnit.SECONDS));
            }
            return snapshot;
        });
        try (var macro = Executors.newSingleThreadExecutor(r -> new Thread(r, "lpq-macro-review"));
             var buffs = mockStatic(BotBuffEffects.class)) {
            buffs.when(() -> BotBuffEffects.showBuff(bot, 4001003)).thenAnswer(inv -> {
                BotAuraState.onAuraShown(bot, 4001003);
                return 120000;
            });
            var work = macro.submit(() -> LudiStages.sneakDoorRooms(bot));
            try {
                assertTrue(sampled.await(5, TimeUnit.SECONDS));
                position.set(new Point(0, -443));
                synchronized (state) {
                    BotAuraState.updateGuardHide(bot);
                }
                assertTrue(BotAuraState.isMonsterImmune(bot));
            } finally {
                resume.countDown();
            }
            work.get(5, TimeUnit.SECONDS);
            assertTrue(BotAuraState.isMonsterImmune(bot), "macro work never owns guard-hide cancellation");
        }
    }

    /**
     * A teleport is not a walk: the anti-tunnel sweep must not span it.
     *
     * <p>Reproduces the reported "hidden thief loses ~500 HP on the safe box ledge": the bot crossed
     * the stage-5 guard room while nobody was on the map (the driver advances such a bot
     * analytically - one tick can be 1-4s of travel), so the contact layer never refreshed its
     * "previous foot pixel". When a real player then entered, the next observed tick swept one
     * rectangle from the entry landing all the way to the box ledge, through the golem standing
     * between them, and the golem's PAD 999 hit landed on a bot that was 189px above it and could
     * not have been touched.
     */
    @Test
    void anUnobservedCrossingCannotLandTheGuardOnArrival() throws Exception {
        MapleMap map = realMap(922010506);
        createBot(map, new Point(179, -223), Job.THIEF);            // st00, the room's entry landing
        when(bot.getCurrentMaxHp()).thenReturn(5000);               // a hit that lands would really cost HP
        inject(map, monster(9300013, 99121, new Point(-80, -441)));

        // One observed contact beat at the landing: the sweep anchor is a real neighbouring pixel.
        ObserverTracker.markObservedNow(map.getId());
        BotContactDamage.tickMobDamage(state, bot);
        assertNotNull(state.lastMobTouchCheckPos, "sanity: an observed beat seeds the anchor");
        verify(bot, never()).safeAddHP(anyInt());

        // The player leaves; the bot crosses the golem's floor to the box ledge with nobody watching.
        // Nothing about that stretch is a physics walk - the driver advances such a bot analytically
        // (tryCoarseAdvance writes the body straight to an interpolated point, one tick being 1-4s of
        // travel), so the contact layer must not keep the landing as "the previous pixel".
        MovementClock.advance(2_500L);
        bot.setPosition(new Point(-83, -630));
        BotContactDamage.tickMobDamage(state, bot);
        assertNull(state.lastMobTouchCheckPos,
                "an unobserved stretch is not a walk: its anchor must not survive into the next sweep");

        // The player walks in: the first observed beat must not read that crossing as a walk.
        ObserverTracker.markObservedNow(map.getId());
        BotContactDamage.tickMobDamage(state, bot);
        verify(bot, never()).safeAddHP(anyInt());
    }

    /**
     * The same rule at the other discontinuity: a rescue / wedge / promotion teleport.
     *
     * <p>{@code resetEntryStateAfterTeleport} is the single funnel every snap goes through, but it
     * only cleared NAV state - the contact sweep's anchor survived, so the first tick after a snap
     * swept the whole teleport distance and hit everything on the way.
     */
    @Test
    void aTeleportCannotLandTheGuardOnArrival() throws Exception {
        MapleMap map = realMap(922010506);
        createBot(map, new Point(179, -223), Job.THIEF);
        when(bot.getCurrentMaxHp()).thenReturn(5000);
        inject(map, monster(9300013, 99122, new Point(-80, -441)));
        ObserverTracker.markObservedNow(map.getId());
        BotContactDamage.tickMobDamage(state, bot);
        assertNotNull(state.lastMobTouchCheckPos, "sanity: an observed beat seeds the anchor");

        // A recover-snap from the entry landing up to the box ledge, then the next contact beat.
        BotPhysicsEngine.teleportTo(state, bot, new Point(-83, -630));
        BotMovementManager.resetEntryStateAfterTeleport(state);
        assertNull(state.lastMobTouchCheckPos, "a teleport is not a walk: the anchor dies with it");

        BotContactDamage.tickMobDamage(state, bot);
        verify(bot, never()).safeAddHP(anyInt());
    }

    private void tick() throws Exception {
        ObserverTracker.markObservedNow(bot.getMapId());
        runTick();
    }

    private void runTick() throws Exception {
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        try {
            tick.invoke(null, state);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof AssertionError assertion) throw assertion;
            throw e;
        }
        MovementClock.advance(50);
    }

    private void createBot(MapleMap map, Point initial, Job job) throws Exception {
        // Synthetic rooms reuse some real map ids to exercise map-scoped policy. Their
        // geometry must replace any earlier in-memory graph, never load an unrelated disk cache -
        // but a room whose geometry this class already baked does not need to be baked again.
        TestGraphBake.ensure(map, BotMovementProfile.base());
        AtomicReference<Point> position = new AtomicReference<>(initial);
        int mapId = map.getId();
        bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(BOT_ID); when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId); when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(40); when(bot.getHp()).thenReturn(5000);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100); when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        when(bot.getParty()).thenReturn(null); when(bot.getEventInstance()).thenReturn(null);
        when(bot.getInventory(any()).getItem(anyShort())).thenReturn(null);
        when(bot.getClient().getChannel()).thenReturn(1); when(bot.getClient().getWorld()).thenReturn(0);
        doAnswer(inv -> { position.set(new Point(inv.getArgument(0))); return null; })
                .when(bot).setPosition(any());
        state = new BotMovementState(bot, null);
        state.movementProfile = BotMovementProfile.base();
        state.lastMap = map; state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        state.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(state, bot, initial);
        states().put(BOT_ID, state);
    }

    private static MapleMap map(int id) {
        MapleMap map = new MapleMap(id, 0, 0, 922010000, 0f);
        map.setMapLineBoundings(-4000, 4000, -4000, 4000);
        map.setFootholds(new FootholdTree(new Point(-4000, -4000), new Point(4000, 4000)));
        return map;
    }

    private static MapleMap attackMap() {
        MapleMap map = map(922019995);
        floor(map, 1, -100, 0, 800);
        TestGraphBake.ensure(map, BotMovementProfile.base()); // bake real geometry before Mockito wraps hot accessors
        return spy(map);
    }

    private static MapleMap realMap(int id) {
        var source = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
        assumeTrue(source.getData(BotNavigationMapLoader.getMapName(id)) != null, "GMS083 Map.wz required");
        return BotNavigationMapLoader.loadMapGeometry(id);
    }

    private static void floor(MapleMap map, int id, int left, int y, int right) {
        map.getFootholds().insert(new Foothold(new Point(left, y), new Point(right, y), id));
    }

    private static Monster monster(int id, int oid, Point position) {
        Monster mob = mock(Monster.class, RETURNS_DEEP_STUBS);
        when(mob.getId()).thenReturn(id); when(mob.getObjectId()).thenReturn(oid);
        when(mob.getType()).thenReturn(MapObjectType.MONSTER); when(mob.getPosition()).thenReturn(position);
        when(mob.isAlive()).thenReturn(true); when(mob.getName()).thenReturn("regression target");
        return mob;
    }

    private static Reactor reactor(int id, int oid, Point position) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getId()).thenReturn(id); when(reactor.getObjectId()).thenReturn(oid);
        when(reactor.getType()).thenReturn(MapObjectType.REACTOR); when(reactor.getPosition()).thenReturn(position);
        when(reactor.isAlive()).thenReturn(true); when(reactor.isActive()).thenReturn(true);
        when(reactor.getReactorType()).thenReturn(100);
        return reactor;
    }

    @SuppressWarnings("unchecked")
    private static void inject(MapleMap map, MapObject object) throws Exception {
        Field objects = MapleMap.class.getDeclaredField("mapobjects"); objects.setAccessible(true);
        ((Map<Integer, MapObject>) objects.get(map)).put(object.getObjectId(), object);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        Field states = GCMovement.class.getDeclaredField("STATES"); states.setAccessible(true);
        return (Map<Integer, BotMovementState>) states.get(null);
    }
}
