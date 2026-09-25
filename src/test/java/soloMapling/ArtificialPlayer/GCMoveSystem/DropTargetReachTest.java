package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Eos Tower (玩具塔) floor 1 "the bot down-jumps off the upper platform
 * and the mid platforms never catch it — it falls to the bottom" report.
 *
 * <p>Two halves of the fix, both pinned on the pure decision rule the DROP executor now uses
 * ({@code arcLandsInTargetRegion}, shared with the JUMP edge's launch re-check): a straight
 * down-jump may only fire when the landing simulated from the bot's LIVE pixel resolves to the
 * edge's own target region — not merely "some foothold exists below" (the old
 * {@code hasDownJumpLanding} void guard).
 *
 * @see JumpArcReachTest (the same rule for horizontal JUMP edges)
 */
class DropTargetReachTest {

    private static final Foothold TARGET_FH = new Foothold(new Point(-114, 952), new Point(-66, 952), 216);
    private static final Foothold STRAY_FH = new Foothold(new Point(0, 0), new Point(10, 0), 999);
    private static final Map<Integer, Integer> REGION_BY_FH = Map.of(216, 20);

    @Test
    void acceptsALiveSimulatedLandingOnTheEdgeTargetRegion() {
        // The whole point of the re-check: the arc simulated from the live launch pixel touches
        // down on the platform the edge promises. Fire.
        BotPhysicsEngine.JumpLanding landing =
                new BotPhysicsEngine.JumpLanding(new Point(-114, 952), TARGET_FH);
        assertTrue(BotNavigationGraphProvider.arcLandsInTargetRegion(landing, 20, REGION_BY_FH),
                "a landing on the edge's target region is a valid drop");
    }

    @Test
    void rejectsADropThatGrazesADifferentPlatformBelow() {
        // The report's shape: mid platforms beside (not under) the column leave a tall unbroken
        // shaft; a live pixel one step outside the validated window drops onto a DIFFERENT
        // platform than the edge promised. Withhold the launch.
        BotPhysicsEngine.JumpLanding landing =
                new BotPhysicsEngine.JumpLanding(new Point(5, 0), STRAY_FH);
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(landing, 20, REGION_BY_FH),
                "a drop landing outside the target region must not fire");
    }

    @Test
    void rejectsADropWithNoLandingAtAll() {
        // The old void-guard case (empty column below) must still refuse, now via the same rule.
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(null, 20, REGION_BY_FH),
                "no landing means no drop");
        BotPhysicsEngine.JumpLanding noFh =
                new BotPhysicsEngine.JumpLanding(new Point(-114, 952), null);
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(noFh, 20, REGION_BY_FH),
                "a landing with no foothold cannot be attributed to a region");
    }

    @Test
    void anUnmappedFootholdIsNeverTreatedAsTheDropTarget() {
        BotPhysicsEngine.JumpLanding landing =
                new BotPhysicsEngine.JumpLanding(new Point(5, 0), STRAY_FH);
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(landing, 20, Map.of()),
                "a foothold absent from the region map resolves to -1 and must never match");
    }
}
