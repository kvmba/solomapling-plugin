package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The reported "bot attacks a mob, then instantly walks/jumps, nothing like a real player" defect.
 *
 * <p>The root cause was structural, not a missing constant: the movement layer had no "attacking"
 * state at all ({@code BotMovementState.attackCooldownMs} was written only by clearers and never
 * set), so every movement path — the fight approach, the post-kill loot walk, the WAIT re-centre, a
 * turn/kite/AoE step, a fidget hop — could put a walk/jump packet on the wire one combat beat
 * (250ms) after the swing, truncating an 800ms animation. The only guard was a 500ms self-owned
 * timer inside the grind brain, and it was consulted by four approach helpers only.
 *
 * <p>The fix moves the rule into the movement layer, where it cannot be bypassed:
 * {@code GCMovement.markAttackAction} arms {@link GCMovement#ATTACK_ACTION_LOCK_MS} at the exact
 * point an attack packet is broadcast, and the driver holds the body for that window. This test
 * drives the PRODUCTION driver tick on a virtual clock (like {@code TowerMazeExecutionSimTest})
 * and pins the four properties that make the fix safe:
 *
 * <ol>
 *   <li>a walk issued right after a swing does not start until the lock elapses;</li>
 *   <li>the goal is NOT dropped — it is only delayed (and the no-progress watchdog stays quiet);</li>
 *   <li>an airborne bot is never frozen by the lock (the "hangs in the jump pose" failure);</li>
 *   <li>a swing that was refused by the driver never arms the lock.</li>
 * </ol>
 */
public class AttackActionLockExecutionSimTest {

    private static final int MAP_ID = 100000000; // Henesys: a flat, wide floor
    private static MapleMap map;

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        try {
            var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
            setter.setAccessible(true);
            setter.invoke(new ServerManager(), ctx);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(int startX, int startY) {
        AtomicReference<Point> pos = new AtomicReference<>(new Point(startX, startY));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getId()).thenReturn(4242);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(startX, startY));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return new Sim(st, pos);
    }

    private static final java.lang.reflect.Method TICK;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** One production driver tick, then advance the virtual clock by one physics tick. */
    private static void tick(Sim sim) {
        try {
            TICK.invoke(null, sim.st());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
    }

    /**
     * Arms the lock exactly the way the attack layer does. Production reaches this through
     * {@code GCMovement.markAttackAction(Character)}, which needs the bot registered in STATES;
     * here the state is driven directly (the production seam is exercised end-to-end by
     * {@link #aRefusedSwingNeverArmsTheLock()} below).
     */
    private static void markAttacked(Sim sim) {
        sim.st().attackActionUntilMs = MovementClock.nowMs() + GCMovement.ATTACK_ACTION_LOCK_MS;
    }

    /** Mirrors GCMovement.move's state writes without the enable()/driver-thread detour. */
    private static void move(Sim sim, int x, int y) {
        BotMovementState st = sim.st();
        st.moveTarget = new Point(x, y);
        st.moveTargetMap = map;
        st.moveTargetPrecise = true;
        st.moveTargetSource = "test";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();
        BotMovementManager.clearNavigationState(st);
    }

    @Test
    void aWalkIssuedRightAfterASwingWaitsOutTheWholeAnimation() {
        Sim sim = sim(0, -100);
        markAttacked(sim);
        int startX = sim.p().x;
        move(sim, startX + 200, sim.p().y);

        // 19 ticks = 950ms: still inside the 1s lock. The walk must not have started.
        for (int i = 0; i < 19; i++) {
            tick(sim);
        }
        assertEquals(startX, sim.p().x,
                "a walk issued after a swing must not move the bot before the animation has played out");

        // Past the lock the pending goal resumes — nothing was dropped.
        for (int i = 0; i < 10; i++) {
            tick(sim);
        }
        assertTrue(sim.p().x != startX,
                "the move issued during the swing lock must resume once the lock elapses, not be lost");
    }

    @Test
    void theHoldIsNotMistakenForAStall() {
        Sim sim = sim(0, -100);
        markAttacked(sim);
        long armedAt = MovementClock.nowMs();
        move(sim, sim.p().x + 200, sim.p().y);

        // The grounded no-progress window is 8s; sit in the hold for a full 1s and then walk. The
        // goal must still be armed the moment the hold ends (the watchdog was fed the same
        // "deliberately standing still" heartbeat the stun and stop-reaction holds use).
        for (int i = 0; i < 20; i++) {
            tick(sim);
        }
        assertTrue(sim.st().moveTarget != null,
                "standing out the swing animation is intentional, not a stalled move");
        assertTrue(MovementClock.nowMs() - sim.st().moveProgressAtMs <= BotPhysicsEngine.cfg.TICK_MS,
                "the hold must keep the no-progress clock fresh so no stall net abandons the goal");
        assertTrue(sim.st().moveProgressAtMs >= armedAt,
                "the hold must never rewind the progress clock");
    }

    @Test
    void anAirborneBotIsNeverFrozenByTheLock() {
        Sim sim = sim(0, -100);
        BotMovementState st = sim.st();
        // Launch a hop, then arm the lock mid-flight the way an engage jump-attack does.
        BotMovementManager.initiateJump(st, st.bot, 0);
        assertTrue(st.inAir, "precondition: the hop is in flight");
        markAttacked(sim);
        int startY = sim.p().y;

        for (int i = 0; i < 6; i++) {
            tick(sim);
        }
        assertTrue(sim.p().y != startY,
                "an airborne bot must keep integrating under the lock - freezing it is the "
                        + "'hangs in the jump pose' failure mode");

        for (int i = 0; i < 200 && st.inAir; i++) {
            tick(sim);
        }
        assertFalse(st.inAir, "and the hop must still complete (land) under the lock");
    }

    @Test
    void markAttackActionArmsTheMovementLock() throws Exception {
        Sim sim = sim(0, -100);
        register(sim.st());
        try {
            assertFalse(GCMovement.isAttackActionLocked(sim.st().bot),
                    "precondition: a fresh bot is not mid-swing");
            GCMovement.markAttackAction(sim.st().bot);
            assertTrue(GCMovement.isAttackActionLocked(sim.st().bot),
                    "the attack broadcast must take the body for the animation window");
            assertEquals(MovementClock.nowMs() + GCMovement.ATTACK_ACTION_LOCK_MS,
                    sim.st().attackActionUntilMs,
                    "the window must be the full ATTACK_ACTION_LOCK_MS from the broadcast");
        } finally {
            unregister(sim.st());
        }
    }

    @Test
    void aSwingTheDriverRefusedDoesNotArmTheLock() throws Exception {
        // A swing that never reaches a packet (here: the driver's own "bot or map is null" gate)
        // must not freeze the body. Arming on the DECISION instead of the broadcast is what made the
        // old 500ms timer punish misses and cooldown-refused beats.
        Sim sim = sim(0, -100);
        register(sim.st());
        try {
            when(sim.st().bot.getMap()).thenReturn(null);
            var r = soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver.botAttack(sim.st().bot);
            assertFalse(r.hit(), "precondition: no map, so no swing is broadcast");
            assertEquals(0L, sim.st().attackActionUntilMs,
                    "a refused/missed swing must not take the body: only a broadcast attack does");
        } finally {
            unregister(sim.st());
        }
    }

    @Test
    void theAttackBroadcastIsTheOnlyArmingSeam() throws Exception {
        // Structural guard: the lock is armed from the packet broadcast, not from the decision that
        // led to it. BotAttackEffects.broadcastAndApply is the one place every strike route (melee,
        // ranged, magic) funnels through.
        String res = soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects.class
                .getName().replace('.', '/') + ".class";
        try (var in = soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects.class
                .getClassLoader().getResourceAsStream(res)) {
            String pool = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(pool.contains("markAttackAction"),
                    "the strike broadcast must arm the attack action lock");
        }
    }

    private static void register(BotMovementState st) throws Exception {
        states().put(st.bot.getId(), st);
    }

    private static void unregister(BotMovementState st) throws Exception {
        states().remove(st.bot.getId());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Integer, BotMovementState> states() throws Exception {
        var f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        return (java.util.Map<Integer, BotMovementState>) f.get(null);
    }


    @Test
    void theLockCoversTheLongestBasicSwingAnimation() {
        // wz/Character.wz/00002000.img.xml: swingO1/2/3, swingT1/2/3, swingP1/2, stabO1/2, shoot1 all
        // total 800ms; shoot2 is the longest basic pose at 820ms. The lock must outlast them so the
        // next walk packet cannot truncate the last frames.
        assertTrue(GCMovement.ATTACK_ACTION_LOCK_MS >= 820L,
                "lock must cover the longest basic swing pose in the WZ body data");
    }

    @Test
    void everyBroadcastSwingInTheCosmeticAttackPackArmsTheLock() throws Exception {
        // Structural guard, same shape as theAttackBroadcastIsTheOnlyArmingSeam: BotAttack's four
        // broadcast methods (basic/skill/ranged/magic swing) put attack packets on the wire too -
        // the reactor chase (PqActions.hitReactor -> basicSwing) and the flavour expressions live
        // here. A swing this pack broadcasts without arming is exactly the hole that let the
        // combat sweep stamp a real strike packet over a live box-breaking animation. Counted per
        // INVOKE instruction (the constant pool dedupes the method reference, so a string count
        // would always read 1); byte-buddy's shaded ASM is on the test classpath.
        String res = soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack.class
                .getName().replace('.', '/') + ".class";
        try (var in = soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack.class
                .getClassLoader().getResourceAsStream(res)) {
            int[] armings = {0};
            new net.bytebuddy.jar.asm.ClassReader(in.readAllBytes()).accept(
                    new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                        @Override
                        public net.bytebuddy.jar.asm.MethodVisitor visitMethod(
                                int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            net.bytebuddy.jar.asm.MethodVisitor mv =
                                    super.visitMethod(access, name, descriptor, signature, exceptions);
                            return new net.bytebuddy.jar.asm.MethodVisitor(
                                    net.bytebuddy.jar.asm.Opcodes.ASM9, mv) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner,
                                                            String mname, String mdescriptor,
                                                            boolean isInterface) {
                                    if ("markAttackAction".equals(mname)
                                            && owner.endsWith("GCMovement")) {
                                        armings[0]++;
                                    }
                                    super.visitMethodInsn(opcode, owner, mname, mdescriptor, isInterface);
                                }
                            };
                        }
                    }, 0);
            assertTrue(armings[0] >= 4,
                    "each of basicSwing/skillSwing/rangedSwing/magicSwing must arm the attack "
                            + "action lock at its broadcast (found " + armings[0] + ")");
        }
    }
}
