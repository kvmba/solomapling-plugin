package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.game.CharacterStance;
import org.gms.manager.ServerManager;
import org.gms.net.packet.Packet;
import org.gms.property.ServiceProperty;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackAction;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.server.MethodScheduler;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real packet boundary without WZ files, a server, or background ticks. */
class ActionPacketTransitionTest {
    private Character bot;
    private MapleMap map;
    private BotMovementState state;
    private final List<Packet> packets = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(ctx.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messages);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messages);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messages);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
    }

    @BeforeEach
    void walkingBot() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        map = mock(MapleMap.class);
        when(map.getId()).thenReturn(987654321);
        FootholdTree ground = new FootholdTree(new Point(-500, -100), new Point(500, 100));
        ground.insert(new Foothold(new Point(-500, 0), new Point(500, 0), 1));
        when(map.getFootholds()).thenReturn(ground);
        bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(7654321);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getJob()).thenReturn(Job.BEGINNER);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(987654321);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(map.getCharacterById(7654321)).thenReturn(bot);
        doAnswer(inv -> {
            packets.add(inv.getArgument(1));
            return null;
        }).when(map).broadcastMessage(eq(bot), any(Packet.class), eq(false));
        state = new BotMovementState(bot, null);
        state.lastMap = map;
        state.movementProfile = BotMovementProfile.base();
        state.moveDir = 1;
        state.hspeed = 1.0;
        state.movementVelX = 125;
        state.moveTarget = new Point(200, 0);
        states().put(bot.getId(), state);
        ObserverTracker.markObservedNow(map.getId());
        BotMovementManager.broadcastMovement(state);
        packets.clear();
    }

    @AfterEach
    void cleanup() throws Exception {
        states().remove(bot.getId(), state);
        MovementClock.reset();
    }

    private void swing(String route) {
        Monster mob = mock(Monster.class, RETURNS_DEEP_STUBS);
        Map<Monster, List<Integer>> hits = Map.of(mob, List.of(0));
        BotAttackAction.tryRun(bot, false, false, () -> switch (route) {
            case "ranged" -> BotAttackEffects.rangedStrike(bot, hits, 0, 0, 0, 22, 0, 4, (short) 0);
            case "magic" -> BotAttackEffects.magicStrike(bot, hits, 2001004, 1, 28, 0, 4, (short) 0);
            default -> BotAttackEffects.meleeStrike(bot, hits, 0, 0, 5, 0, 4, (short) 0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"melee", "ranged", "magic"})
    void aWalkingBodySendsAZeroVelocityStopBeforeTheAttack(String route) throws Exception {
        try (var scheduler = mockStatic(MethodScheduler.class)) {
            GCMovement.stop(bot);
            swing(route);
            assertEquals(2, packets.size(), "stop frame must precede the attack, not follow it");
            byte[] stop = packets.getFirst().getBytes();
            // MOVE_PLAYER: opcode(2), character id(4), reserved(4), count(1), command(1),
            // position(4), velocity(4), foothold(2), stance(1), duration(2).
            assertEquals(0, shortAt(stop, 16));
            assertEquals(0, shortAt(stop, 18));
            assertEquals(CharacterStance.ALERT_RIGHT_STANCE, Byte.toUnsignedInt(stop[22]));
            assertEquals(0, state.moveDir);
            assertEquals(0.0, state.hspeed);
            assertEquals(0, state.movementVelX);
            assertTrue(GCMovement.isAttackActionLocked(bot));
            state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
            var tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            tick.setAccessible(true);
            for (int i = 0; i < 19; i++) {
                tick.invoke(null, state);
                MovementClock.advance(50);
            }
            assertEquals(2, packets.size(), "no post-attack movement frame may cancel the animation");
        }
    }

    @Test
    void aRefusedDecisionDoesNotStopOrLockTheBody() {
        assertFalse(BotAttackAction.tryRun(bot, false, false, () -> false));
        try (var attacks = mockStatic(soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack.class)) {
            var noTarget = BotAttackDriver.botAttack(bot);
            assertFalse(noTarget.hit());
            assertTrue(noTarget.reason().contains("no targetable mobs"));
        }
        assertEquals(1, state.moveDir);
        assertEquals(1.0, state.hspeed);
        assertEquals(125, state.movementVelX);
        assertEquals(new Point(200, 0), state.moveTarget);
        assertTrue(packets.isEmpty());
        assertFalse(GCMovement.isAttackActionLocked(bot));
    }

    @Test
    void anAirAttackKeepsItsArcAndDoesNotSendAGroundStop() {
        state.inAir = true;
        state.airVelX = 7;
        state.velY = -10;
        try (var scheduler = mockStatic(MethodScheduler.class)) {
            swing("melee");
            assertEquals(1, packets.size());
            assertTrue(state.inAir);
            assertEquals(7, state.airVelX);
            assertEquals(-10, state.velY);
        }
    }

    @Test
    void aCooldownRefusalDoesNotStopTheBody() throws Exception {
        var field = BotAttackDriver.class.getDeclaredField("nextAttackByBot");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> cooldowns = (Map<Integer, Long>) field.get(null);
        cooldowns.put(bot.getId(), Long.MAX_VALUE);
        try {
            var result = BotAttackDriver.botAttack(bot);
            assertEquals("on cooldown", result.reason());
            assertEquals(1, state.moveDir);
            assertEquals(125, state.movementVelX);
            assertTrue(packets.isEmpty());
            assertFalse(GCMovement.isAttackActionLocked(bot));
        } finally {
            cooldowns.remove(bot.getId());
        }
    }

    @Test
    void aFollowUpDoesNotInsertAnotherStopOrDropThePendingGoal() {
        Packet attack = mock(Packet.class);
        try (var scheduler = mockStatic(MethodScheduler.class)) {
            GCMovement.broadcastAttackAction(bot, attack);
            GCMovement.broadcastAttackAction(bot, attack);
            assertEquals(3, packets.size(), "one stop, main swing, follow-up; no intervening move");
            assertSame(attack, packets.get(1));
            assertSame(attack, packets.get(2));
            assertEquals(new Point(200, 0), state.moveTarget);
        }
    }

    @Test
    void everyBodyAttackSenderUsesTheOrderedPacketBoundary() throws Exception {
        Map<Class<?>, List<String>> senders = Map.of(
                BotAttackEffects.class, List.of("broadcastAndApply", "bodyStrike"),
                soloMapling.ArtificialPlayer.BotAttackSystem.BotFinalAttack.class, List.of("maybeTrigger"),
                soloMapling.ArtificialPlayer.BotAttackSystem.BotGroundMists.class, List.of("tryCast"),
                soloMapling.ArtificialPlayer.BotAttackSystem.BotMesoBomb.class, List.of("tryDetonate"),
                soloMapling.ArtificialPlayer.BotAttackSystem.BotShadowMeso.class, List.of("tryThrow"),
                soloMapling.ArtificialPlayer.BotAttackSystem.BotShadowWeb.class, List.of("tryCast"),
                soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack.class,
                        List.of("basicSwingNow", "skillSwingNow", "rangedSwingNow", "magicSwingNow"),
                soloMapling.ArtificialPlayer.SkillShowSystem.SkillShowEffects.class,
                        List.of("castMistNow", "castWebNow", "throwShadowMesoNow"));
        for (var sender : senders.entrySet()) {
            List<String> ordered = new ArrayList<>();
            String resource = sender.getKey().getName().replace('.', '/') + ".class";
            try (var in = sender.getKey().getClassLoader().getResourceAsStream(resource)) {
                new net.bytebuddy.jar.asm.ClassReader(in.readAllBytes()).accept(
                        new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                            @Override
                            public net.bytebuddy.jar.asm.MethodVisitor visitMethod(int access, String name,
                                    String descriptor, String signature, String[] exceptions) {
                                return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(int opcode, String owner, String method,
                                                                String desc, boolean isInterface) {
                                        if (owner.endsWith("/GCMovement") && method.equals("broadcastAttackAction")) {
                                            ordered.add(name);
                                        }
                                    }
                                };
                            }
                        }, 0);
            }
            assertTrue(ordered.containsAll(sender.getValue()),
                    sender.getKey().getSimpleName() + " must order stop/action/lock at every body attack exit");
        }
    }

    @Test
    void aBundleOfRequestedBuffsWaitsInsteadOfDroppingTheSecondCast() {
        Skill skill = mock(Skill.class);
        when(skill.getMaxLevel()).thenReturn(1);
        List<Runnable> tasks = new ArrayList<>();
        try (var skills = mockStatic(SkillFactory.class);
             var scheduler = mockStatic(MethodScheduler.class)) {
            skills.when(() -> SkillFactory.getSkill(1001003)).thenReturn(skill);
            skills.when(() -> SkillFactory.getSkill(1001004)).thenReturn(skill);
            scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong()))
                    .thenAnswer(inv -> { tasks.add(inv.getArgument(0)); return null; });
            BotBuffEffects.givePartyBuff(bot, 1001003, List.of());
            tasks.clear(); // ignore the separate alert-expiry callback
            BotBuffEffects.givePartyBuff(bot, 1001004, List.of());
            assertEquals(2, packets.size(), "second cast waits out the first, not overwrites it");
            assertEquals(1, tasks.size());
            MovementClock.advance(GCMovement.ATTACK_ACTION_LOCK_MS);
            tasks.removeFirst().run();
            assertEquals(3, packets.size(), "queued buff is eventually shown, no redundant stop");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDelayedBuffRechecksTheRecipientsMap(boolean extended) {
        Character target = mock(Character.class);
        when(target.getId()).thenReturn(42);
        when(target.getMap()).thenReturn(map);
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(skill.getMaxLevel()).thenReturn(1);
        when(skill.getEffect(1)).thenReturn(effect);
        when(effect.getStatups()).thenReturn(List.of());
        List<Runnable> tasks = new ArrayList<>();
        try (var skills = mockStatic(SkillFactory.class);
             var scheduler = mockStatic(MethodScheduler.class);
             var servers = mockStatic(org.gms.net.server.Server.class)) {
            skills.when(() -> SkillFactory.getSkill(1001003)).thenReturn(skill);
            servers.when(org.gms.net.server.Server::getInstance)
                    .thenReturn(mock(org.gms.net.server.Server.class));
            scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong()))
                    .thenAnswer(inv -> { tasks.add(inv.getArgument(0)); return null; });
            state.attackActionUntilMs = MovementClock.nowMs() + GCMovement.ATTACK_ACTION_LOCK_MS;
            if (extended) BotBuffEffects.giveExtendedBuff(bot, 1001003, List.of(target), 60_000);
            else BotBuffEffects.givePartyBuff(bot, 1001003, List.of(target));
            MapleMap otherInstance = mock(MapleMap.class);
            when(otherInstance.getId()).thenReturn(987654321); // same map id is still another instance
            when(target.getMap()).thenReturn(otherInstance);
            MovementClock.advance(GCMovement.ATTACK_ACTION_LOCK_MS);
            tasks.removeFirst().run();
            verify(effect, never()).applyToTarget(bot, target);
            verify(target, never()).sendPacket(any());
            assertTrue(packets.isEmpty(), "a request with no co-located recipients must not cast");
        }
    }

    @Test
    void aRequestedBuffRetryCannotResurrectARemovedBody() {
        state.attackActionUntilMs = MovementClock.nowMs() + GCMovement.ATTACK_ACTION_LOCK_MS;
        List<Runnable> tasks = new ArrayList<>();
        try (var scheduler = mockStatic(MethodScheduler.class)) {
            scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong()))
                    .thenAnswer(inv -> { tasks.add(inv.getArgument(0)); return null; });
            BotBuffEffects.giveExtendedBuff(bot, 1001003, List.of(), 60_000);
            assertEquals(1, tasks.size());
            when(map.getCharacterById(bot.getId())).thenReturn(null);
            tasks.removeFirst().run();
            assertTrue(tasks.isEmpty(), "despawn must terminate the retry chain even with a stale map pointer");
            assertTrue(packets.isEmpty());
        }
    }

    @Test
    void animatedBuffsRecheckAirborneStateAndCannotOverlapAnAttackOwner() {
        Skill skill = mock(Skill.class);
        when(skill.getMaxLevel()).thenReturn(1);
        try (var skills = mockStatic(SkillFactory.class);
             var scheduler = mockStatic(MethodScheduler.class)) {
            skills.when(() -> SkillFactory.getSkill(1001003)).thenReturn(skill);
            state.inAir = true;
            assertEquals(-1, BotBuffEffects.showBuff(bot, 1001003));
            assertTrue(packets.isEmpty());
            state.inAir = false;
            BotAttackAction.tryRun(bot, false, false, () -> {
                assertEquals(-1, BotBuffEffects.showBuff(bot, 1001003));
                return true;
            });
            assertTrue(packets.isEmpty());
            assertFalse(GCMovement.isAttackActionLocked(bot));
        }
    }

    @Test
    void aBusyScheduledBuffRetriesWithoutSpendingItsRecastAndDiesOnCleanup() {
        List<Runnable> tasks = new ArrayList<>();
        try (var config = mockStatic(BotBuffConfig.class);
             var effects = mockStatic(BotBuffEffects.class);
             var scheduler = mockStatic(MethodScheduler.class)) {
            config.when(() -> BotBuffConfig.buffsForJob(Job.BEGINNER)).thenReturn(List.of(1001003));
            effects.when(() -> BotBuffEffects.castBuff(bot, 1001003, false)).thenReturn(-1, 60_000);
            effects.when(() -> BotBuffEffects.durationOf(1001003, bot)).thenReturn(60_000);
            scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong()))
                    .thenAnswer(inv -> { tasks.add(inv.getArgument(0)); return null; });

            assertEquals(1, BotBuffDriver.forceBuff(bot));
            tasks.removeFirst().run(); // busy, so a retry stays pending
            assertEquals(1, tasks.size());
            assertFalse(BotAttackAction.tryRun(bot, false, false, () -> true),
                    "pending casts must get the next free beat before another combat swing");
            state.inAir = true;
            assertTrue(BotAttackAction.tryRun(bot, false, false, () -> true),
                    "a grounded-only buff must not starve legitimate jump attacks");
            state.inAir = false;
            state.swimming = true;
            assertTrue(BotAttackAction.tryRun(bot, false, false, () -> true),
                    "a pending ground cast must not disarm underwater combat");
            state.swimming = false;
            assertEquals(0, BotBuffDriver.forceBuff(bot), "forced sweeps cannot duplicate a pending cast");
            tasks.removeFirst().run(); // now admitted
            assertTrue(tasks.isEmpty());
            BotBuffDriver.botBuff(bot);
            assertTrue(tasks.isEmpty(), "only the successful cast starts the normal recast cooldown");
            assertEquals(1, BotBuffDriver.forceBuff(bot));
            BotBuffDriver.clearBot(bot.getId());
            tasks.removeFirst().run(); // stale callback after despawn
            effects.verify(() -> BotBuffEffects.castBuff(bot, 1001003, false), times(2));
        } finally {
            BotBuffDriver.clearBot(bot.getId());
        }
    }

    @Test
    void aFailedOrOffMapScheduledBuffReleasesItsPendingSlot() {
        List<Runnable> tasks = new ArrayList<>();
        try (var config = mockStatic(BotBuffConfig.class);
             var effects = mockStatic(BotBuffEffects.class);
             var scheduler = mockStatic(MethodScheduler.class)) {
            config.when(() -> BotBuffConfig.buffsForJob(Job.BEGINNER)).thenReturn(List.of(1001003));
            effects.when(() -> BotBuffEffects.castBuff(bot, 1001003, false))
                    .thenThrow(new IllegalStateException("cast failed"));
            scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong()))
                    .thenAnswer(inv -> { tasks.add(inv.getArgument(0)); return null; });
            assertEquals(1, BotBuffDriver.forceBuff(bot));
            assertThrows(IllegalStateException.class, () -> tasks.removeFirst().run());
            assertEquals(1, BotBuffDriver.forceBuff(bot), "a failed task must not strand the pending marker");
            when(bot.getMap()).thenReturn(mock(MapleMap.class));
            tasks.removeFirst().run();
            effects.verify(() -> BotBuffEffects.castBuff(bot, 1001003, false), times(1));
            assertEquals(1, BotBuffDriver.forceBuff(bot), "an abandoned map episode releases the cast too");
        } finally {
            BotBuffDriver.clearBot(bot.getId());
        }
    }

    @Test
    void silentPendingAurasNeverBlockCombat() {
        try (var config = mockStatic(BotBuffConfig.class);
             var scheduler = mockStatic(MethodScheduler.class)) {
            config.when(() -> BotBuffConfig.buffsForJob(Job.BEGINNER)).thenReturn(List.of(1001003));
            assertEquals(1, BotBuffDriver.forceBuff(bot, true));
            assertTrue(BotAttackAction.tryRun(bot, false, false, () -> true));
            assertEquals(0, BotBuffDriver.forceBuff(bot, true));
        } finally {
            BotBuffDriver.clearBot(bot.getId());
        }
    }

    @Test
    void aDeadBodyCannotCastEvenThroughASilentRefresh() {
        when(bot.getHp()).thenReturn(0);
        assertEquals(0, BotBuffEffects.showBuff(bot, 1001003, false));
        assertEquals(0, BotBuffEffects.showBuff(bot, 1001003, true));
        assertEquals(0, BotBuffEffects.castBuff(bot, 1001003));
        BotBuffEffects.givePartyBuff(bot, 1001003, List.of(bot));
        BotBuffEffects.giveExtendedBuff(bot, 1001003, List.of(bot), 60_000);
        assertTrue(packets.isEmpty());
        assertFalse(GCMovement.isAttackActionLocked(bot));
    }

    @Test
    void aRejectedScheduleCannotLeaveCombatBlockedByAPendingCast() {
        try (var config = mockStatic(BotBuffConfig.class);
             var scheduler = mockStatic(MethodScheduler.class)) {
            config.when(() -> BotBuffConfig.buffsForJob(Job.BEGINNER)).thenReturn(List.of(1001003));
            scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong()))
                    .thenThrow(new java.util.concurrent.RejectedExecutionException());
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> BotBuffDriver.forceBuff(bot));
            assertTrue(BotAttackAction.tryRun(bot, false, false, () -> true));
        } finally {
            BotBuffDriver.clearBot(bot.getId());
        }
    }

    @Test
    void animatedBuffsPlantAndOwnTheBodyButSilentAurasDoNot() {
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(skill.getMaxLevel()).thenReturn(1);
        when(skill.getEffect(1)).thenReturn(effect);
        when(effect.getDuration()).thenReturn(60_000);
        when(effect.getStatups()).thenReturn(List.of());
        try (var skills = mockStatic(SkillFactory.class);
             var scheduler = mockStatic(MethodScheduler.class)) {
            skills.when(() -> SkillFactory.getSkill(1001003)).thenReturn(skill);
            assertEquals(60_000, BotBuffEffects.showBuff(bot, 1001003, true));
            assertEquals(1, state.moveDir, "silent arrival aura is not a cast");
            assertTrue(packets.isEmpty());
            assertFalse(GCMovement.isAttackActionLocked(bot));

            assertEquals(60_000, BotBuffEffects.showBuff(bot, 1001003));
            assertEquals(2, packets.size(), "animated cast needs stop then cast packets");
            assertEquals(0, state.movementVelX);
            assertTrue(GCMovement.isAttackActionLocked(bot));
            packets.clear();
            BotBuffEffects.castBuff(bot, 1001003);
            assertTrue(packets.isEmpty(), "another cast must not cut an active animation");
        }
    }

    private static int shortAt(byte[] bytes, int at) {
        return (short) (Byte.toUnsignedInt(bytes[at]) | (Byte.toUnsignedInt(bytes[at + 1]) << 8));
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() throws Exception {
        var field = GCMovement.class.getDeclaredField("STATES");
        field.setAccessible(true);
        return (Map<Integer, BotMovementState>) field.get(null);
    }
}
