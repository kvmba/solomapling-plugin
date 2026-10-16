package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Test helper: register a bot's movement state WITHOUT starting the driver.
 *
 * <p>{@code GCMovement.move} - the production arm path every walk helper uses - calls
 * {@code enable(bot)}, which starts a real self-rescheduling 50ms driver task for that entry. A test
 * that drives a production helper against a Mockito {@link Character} therefore ends up with a live
 * driver thread invoking the mock's {@code getMap()}/{@code getMapId()}/{@code getPosition()}
 * concurrently with the test thread's {@code when(...)} stubbing. Mockito mocks are not thread-safe
 * across that overlap, so the run fails intermittently ({@code WrongTypeOfReturnValue},
 * {@code UnfinishedStubbing}) depending on scheduling - a flake that is purely an artifact of the
 * driver the test never needed.
 *
 * <p>Registering the state first makes {@code enable}'s {@code computeIfAbsent} a no-op, so the
 * production path under test runs against a live entry with no thread behind it (the same shape
 * {@code LpqLiveMobChaseRealSeekTest} builds inline). The {@code STATES} field is private to the
 * engine, so the helper reaches it reflectively - deliberately kept in the test tree, because
 * production's encapsulation is worth more than a test-only accessor.
 */
public final class DriverlessMovementState {

    private DriverlessMovementState() {
    }

    /**
     * Register an idle entry for {@code bot}, settled on the ground under its current position -
     * the same settle {@code enable()} applies, so the walk helpers see what they would in
     * production.
     */
    public static void inject(Character bot) {
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        MapleMap map = bot.getMap();
        if (map != null) {
            st.lastMapId = bot.getMapId();
            st.fhIndex = BotMovementManager.buildFhIndex(map);
            Point cur = bot.getPosition();
            Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(cur.x, cur.y - 1));
            BotPhysicsEngine.teleportTo(st, bot, GCMovement.settledPoint(cur, ground));
            BotMovementManager.resetEntryStateAfterTeleport(st);
        }
        states().put(bot.getId(), st);
    }

    /** Drop the entry, so no test inherits the previous test's move target or entry state. */
    public static void remove(int botId) {
        states().remove(botId);
    }

    /** The live move goal of the bot's entry, or null (what {@code hasMoveTarget} reads). */
    public static Point moveTargetOf(int botId) {
        BotMovementState st = states().get(botId);
        return st == null ? null : st.moveTarget;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, BotMovementState> states() {
        try {
            Field f = GCMovement.class.getDeclaredField("STATES");
            f.setAccessible(true);
            return (Map<Integer, BotMovementState>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GCMovement.STATES is unreachable", e);
        }
    }
}
