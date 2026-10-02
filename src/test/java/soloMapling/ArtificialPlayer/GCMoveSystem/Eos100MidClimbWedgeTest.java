package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 玩具塔100层 (221024400) mid-ladder wedge, pinned on the REAL map geometry + PRODUCTION
 * driver tick. The second-from-left ladder (x=-130, span 858..1119) is entered ONLY by a
 * jump-grab (its bottom hangs 48px below the y=1071 row), so every bot attaches at the
 * same Y and the 5px climb-step grid folds the same way every time.
 *
 * <p>The wedge: a move abandoned mid-climb (the 2s airborne no-progress give-up) used to
 * leave the driver steering toward the bot's OWN position — dy=0 -> MoveAction.idle() ->
 * holdClimb froze the climber pixel-still mid-ladder, and every driver-level watchdog
 * exempts climbing, so nothing ever dislodged it (the "爬一下就不动，几秒后被传送走"
 * report). The fix: giveUpStalledMove dismounts a climber toward ground instead of
 * abandoning it into the self-aim hold, and tickClimbStallEscape backstops any parked
 * climber (no position change ~8px / 4s while not resting) with a driver-level kick.
 */
public class Eos100MidClimbWedgeTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        MovementClock.useVirtual(1_000_000L); // wall-clock gates (stall windows) elapse per tick
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
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
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset(); // the virtual clock must not leak into other test classes
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos) {
        Point p() { return pos.get(); }
    }

    private static Sim sim(int mapId, int spawnX, int spawnY, BotMovementProfile profile) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraphProvider.getGraph(map, profile);

        AtomicReference<Point> pos = new AtomicReference<>(new Point(spawnX, spawnY));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getId()).thenReturn(777);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMapId = mapId;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(spawnX, spawnY));
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

    private static void tickN(Sim sim, int ticks, int mapId) {
        for (int i = 0; i < ticks; i++) {
            if (i % 20 == 0) {
                ObserverTracker.markObservedNow(mapId);
            }
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
        }
    }

    private static void move(BotMovementState st, int x, int y) {
        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();
    }

    private static org.gms.server.maps.Rope secondLadder(Sim sim) {
        for (org.gms.server.maps.Rope r : sim.st().bot.getMap().getRopes()) {
            if (r.x() == -130) {
                return r;
            }
        }
        throw new IllegalStateException("second ladder (x=-130) not found on 221024400");
    }

    /**
     * THE WEDGE (pre-fix froze at (169,1211) pixel-still forever, verified on v66 and v67):
     * grab the ladder mid-way, abandon the move mid-climb (what giveUpStalledMove used to
     * leave behind), run 6s with no goal. The driver must dislodge the climber — after the
     * fix the abandon itself kicks the bot off the rope at once.
     */
    @Test
    void midClimbMoveAbandonDoesNotFreezeTheClimber() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(221024400, 20, 1276, profile);
        BotPhysicsEngine.attachToRope(sim.st(), sim.st().bot, secondLadder(sim), 1000);
        sim.st().moveTarget = null; // the abandon result: no goal, still climbing
        sim.st().moveBestDist = Integer.MAX_VALUE;
        BotMovementManager.clearNavigationState(sim.st());
        Point atAbandon = sim.p();

        tickN(sim, 120, 221024400); // 6s of driver ticks with NO goal
        Point end = sim.p();
        org.junit.jupiter.api.Assertions.assertFalse(sim.st().climbing,
                "climber was abandoned mid-ladder and froze (parked climber never dislodged): "
                        + atAbandon + " -> " + end);
        org.junit.jupiter.api.Assertions.assertFalse(sim.st().inAir && sim.p().equals(atAbandon),
                "the escape kicked the bot off the rope but it froze mid-air at " + end
                        + " (idleOnGround pinned the dismount)");
        org.junit.jupiter.api.Assertions.assertTrue(
                BotPhysicsEngine.findGroundFoothold(sim.st().bot.getMap(), end) != null,
                "post-dismount the bot must LAND on a foothold, ended at " + end);
    }

    /**
     * The OTHER wedge path: a move whose stall clock fires WHILE the bot is already climbing
     * (giveUpStalledMove with a live moveTarget, climbing=true) must kick the climber off the
     * rope instead of clearing the target and steering the driver at the bot's own position.
     */
    @Test
    void stalledMoveGiveUpDismountsAClimber() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(221024400, 20, 1276, profile);
        BotPhysicsEngine.attachToRope(sim.st(), sim.st().bot, secondLadder(sim), 1000);
        move(sim.st(), -130, 856);
        // Burn the airborne stall window while pinned on the rope (no vertical progress: the
        // climb intent is never armed because the attach is the test's, not a nav execution)
        tickN(sim, 45, 221024400); // > AIR_MOVE_STALL_MS with dist stuck
        Point after = sim.p();
        org.junit.jupiter.api.Assertions.assertFalse(sim.st().climbing,
                "give-up on a stalled climbing move must dismount the climber, still climbing at " + after);
    }

    /**
     * Guard: a HEALTHY climb to the y=856 row over the same ladder still completes — the
     * climber-escape must not rescue-hop legitimate climbs (one climbs 250px+ in well under
     * the 4s window, moving every tick).
     */
    @Test
    void healthySecondLadderClimbStillCompletes() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(221024400, -220, 1071, profile);
        // Re-issue per 250ms grind beat, exactly like the strategy layer does (RoamStrategy /
        // CampStrategy re-issue the approach every beat) — a give-up clears the goal, and the
        // next beat re-plans from where the bot stands.
        for (int beat = 0; beat < 80; beat++) { // 20s: approach hops + jump-grab retries + climb
            move(sim.st(), -130, 856);
            tickN(sim, 5, 221024400);
        }
        Point end = sim.p();
        org.junit.jupiter.api.Assertions.assertTrue(end.y <= 856,
                "healthy climb to the y=856 row must still complete, ended at " + end);
    }
}
