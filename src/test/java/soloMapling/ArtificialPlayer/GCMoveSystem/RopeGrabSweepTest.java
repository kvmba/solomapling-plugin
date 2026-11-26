package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RopeGrabSweepTest {
    @BeforeAll
    static void stubHost() throws Exception {
        PqParkDoorExecutionSimTest.stubHostEnvironment();
    }

    @AfterAll
    static void resetClock() {
        MovementClock.reset();
    }

    @ParameterizedTest
    @CsvSource({"1,true", "-1,false"})
    void bakeAndDriverCatchBetweenSamplesInBothDirections(int direction, boolean ladder) {
        MapleMap map = map(direction, false);
        Rope rope = new Rope(0, -200, -32, ladder);
        map.addRope(rope);
        Point launch = new Point(-65 * direction, 0);
        var profile = BotMovementProfile.base();
        int stepX = BotPhysicsEngine.walkStep(map, profile) * direction;

        // The descending step starts outside the grip horizontally and ends below the rope.
        Point predicted = BotPhysicsEngine.simulateGroundJumpRopeGrab(map, launch, stepX, rope, profile);
        assertNotNull(predicted);
        assertTrue(predicted.y < rope.bottomY());
        var state = jump(map, launch, rope, stepX);
        replayFlight(state);
        assertTrue(state.climbing);
        assertEquals(predicted, state.bot.getPosition());
    }

    @Test
    void sweepCannotGrabPastAnEarlierLanding() {
        MapleMap map = map(1, true);
        Rope rope = new Rope(0, -200, -32, false);
        map.addRope(rope);
        Point launch = new Point(-65, 0);
        var profile = BotMovementProfile.base();
        int stepX = BotPhysicsEngine.walkStep(map, profile);

        assertNull(BotPhysicsEngine.simulateGroundJumpRopeGrab(map, launch, stepX, rope, profile));
        var state = jump(map, launch, rope, stepX);
        replayFlight(state);
        assertFalse(state.climbing);
        assertEquals(-43, state.bot.getPosition().y);
    }

    @Test
    void snappingToTheGripCannotCrossASolidWall() {
        MapleMap map = map(1, false);
        var wall = new Foothold(new Point(-4, 60), new Point(-4, -140), 4);
        wall.setPrev(5);
        map.getFootholds().insert(wall);
        map.getFootholds().insert(new Foothold(new Point(-4, 60), new Point(60, 60), 5));
        Rope rope = new Rope(0, -200, -32, true);
        map.addRope(rope);

        Point launch = new Point(-65, 0);
        var profile = BotMovementProfile.base();
        int stepX = BotPhysicsEngine.walkStep(map, profile);
        assertNull(BotPhysicsEngine.simulateGroundJumpRopeGrab(map, launch, stepX, rope, profile));
        var state = jump(map, launch, rope, stepX);
        replayFlight(state);
        assertFalse(state.climbing);
        assertTrue(state.bot.getPosition().x < wall.getX1());
    }

    @Test
    void grabbingStillRequiresIntentAndAnUnlockedMovementState() {
        MapleMap map = map(1, false);
        Rope rope = new Rope(0, -200, -32, true);
        map.addRope(rope);
        Point launch = new Point(-65, 0);
        int stepX = BotPhysicsEngine.walkStep(map, BotMovementProfile.base());
        var withoutIntent = jump(map, launch, rope, stepX);
        withoutIntent.climbUpIntent = false;
        replayFlight(withoutIntent);
        assertFalse(withoutIntent.climbing);

        var locked = jump(map, launch, rope, stepX);
        locked.attackMovementLockUntilMs = MovementClock.nowMs() + 2_000;
        replayFlight(locked);
        assertFalse(locked.climbing);
    }

    private static MapleMap map(int direction, boolean intercept) {
        var map = new MapleMap(-1, 0, 0, -1, 0f);
        map.setMapLineBoundings(-300, 200, -250, 250);
        var footholds = new FootholdTree(new Point(-250, -300), new Point(250, 200));
        footholds.insert(direction > 0
                ? new Foothold(new Point(-250, 0), new Point(-64, 0), 1)
                : new Foothold(new Point(64, 0), new Point(250, 0), 1));
        footholds.insert(new Foothold(new Point(-250, 146), new Point(250, 146), 2));
        if (intercept) footholds.insert(new Foothold(new Point(-12, -43), new Point(80, -43), 3));
        map.setFootholds(footholds);
        return map;
    }

    @Test
    void aPlannedJumpGrabsTheSelectedRopeWhenAnotherRopeIsCloser() {
        MapleMap map = map(1, false);
        var other = new Rope(-40, -200, 0, false);
        var target = new Rope(0, -200, -32, true);
        map.addRope(other);
        map.addRope(target);
        Point launch = new Point(-65, 0);
        int stepX = BotPhysicsEngine.walkStep(map, BotMovementProfile.base());
        var state = jump(map, launch, target, stepX);
        replayFlight(state);
        assertEquals(target, state.climbRope);
    }

    private static BotMovementState jump(MapleMap map, Point launch, Rope target, int stepX) {
        var bot = new CharacterDouble(920700, map, null, null);
        var state = new BotMovementState(bot, null);
        state.movementProfile = BotMovementProfile.base();
        BotPhysicsEngine.teleportTo(state, bot, launch);
        BotMovementManager.jumpToRope(state, bot, target, stepX);
        return state;
    }

    private static void replayFlight(BotMovementState state) {
        for (int tick = 0; tick < 30 && state.inAir; tick++) {
            BotMovementManager.tickAirborne(state, null);
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
        }
        assertFalse(state.inAir);
    }
}
