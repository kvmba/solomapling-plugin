package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Eos Tower (玩具塔) "bounces in place at the ledge and never jumps to the
 * platform beside it" report.
 *
 * <p>A JUMP edge's launch window only bounds WHERE the bot may take off. Execution fires whenever the
 * bot is within one walk step of the selected launch x ({@code isWithinJumpLaunchWindow} tolerates the
 * ±walkStep launch phase — see {@link JumpLaunchGateTest}), and for a window too thin to inset the inset
 * collapses it to a single pixel ({@code insetJumpLaunchWindow}). The phase band can then reach a pixel
 * the builder never validated, and on a small ledge that pixel's arc falls back onto the SOURCE
 * platform: the bot jumps, lands where it started, walks back in, fires again — a perpetual pogo — until
 * a travel/grind watchdog warps it away.
 *
 * <p>The fix re-runs the builder's own flight predicate from the bot's live pixel and only lets the
 * launch fire when that arc actually lands in the edge's target region. This test pins the decision half
 * ({@code arcLandsInTargetRegion}) directly: a real {@code MapleMap} cannot be built in a unit test
 * (Spring static initializer — see {@code SwimWallClimbSimulationTest}), but the accept/reject rule on a
 * landing is pure.
 */
class JumpArcReachTest {

    private static BotNavigationGraph.Region region(int id, int minX, int maxX, int y) {
        Foothold fh = new Foothold(new Point(minX, y), new Point(maxX, y), id);
        return new BotNavigationGraph.Region(id, java.util.List.of(new BotNavigationGraph.Segment(fh, false)));
    }

    /** A landing whose foothold maps to region 20, and a region map that says so. */
    private static final Foothold TARGET_FH = new Foothold(new Point(-114, 952), new Point(-66, 952), 216);
    private static final Map<Integer, Integer> REGION_BY_FH = Map.of(216, 20);

    @Test
    void acceptsAlandingThatReachesTheEdgeTargetRegion() {
        BotPhysicsEngine.JumpLanding landing =
                new BotPhysicsEngine.JumpLanding(new Point(-114, 952), TARGET_FH);
        assertTrue(BotNavigationGraphProvider.arcLandsInTargetRegion(landing, 20, REGION_BY_FH),
                "a landing in the edge's target region is a valid launch");
    }

    @Test
    void rejectsALandingThatFallsBackOntoTheSourceRegion() {
        // The bounce case: fired a step outside the validated span, the arc comes down on the source
        // platform's own foothold (region 14), not the target (20). The launch must be withheld.
        Foothold sourceFh = new Foothold(new Point(-260, 750), new Point(-85, 750), 215);
        Map<Integer, Integer> regionByFh = Map.of(215, 14, 216, 20);
        BotPhysicsEngine.JumpLanding landing =
                new BotPhysicsEngine.JumpLanding(new Point(-85, 750), sourceFh);
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(landing, 20, regionByFh),
                "an arc that lands back on the source platform must not let the launch fire");
    }

    @Test
    void rejectsALaunchWithNoLandingAtAll() {
        // A launch off the map edge (no foothold below) is never a valid crossing.
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(null, 20, REGION_BY_FH),
                "no landing means no crossing");
        BotPhysicsEngine.JumpLanding noFh =
                new BotPhysicsEngine.JumpLanding(new Point(-114, 952), null);
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(noFh, 20, REGION_BY_FH),
                "a landing with no foothold cannot be attributed to a region");
    }

    @Test
    void anUnmappedFootholdIsNeverTreatedAsTheTarget() {
        // A foothold absent from the region map resolves to -1, which must never equal a real region id
        // (that would let a void landing masquerade as arrival on whatever region got id -1). Real JUMP
        // edge targets are always >= 0, so the mapped case is the one that matters.
        Foothold stray = new Foothold(new Point(0, 0), new Point(10, 0), 999);
        BotPhysicsEngine.JumpLanding landing =
                new BotPhysicsEngine.JumpLanding(new Point(0, 0), stray);
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(landing, 20, REGION_BY_FH),
                "an unmapped foothold is not the target region");
    }

    @Test
    void theDecisionUsesTheFootholdsRegionId() {
        // Symmetry check: both sides of a real crossing (same target foothold) are accepted, and the
        // rule reads the foothold's region rather than the landing point's coordinates.
        BotPhysicsEngine.JumpLanding leftEdge =
                new BotPhysicsEngine.JumpLanding(new Point(-114, 952), TARGET_FH);
        BotPhysicsEngine.JumpLanding rightEdge =
                new BotPhysicsEngine.JumpLanding(new Point(-66, 952), TARGET_FH);
        assertTrue(BotNavigationGraphProvider.arcLandsInTargetRegion(leftEdge, 20, REGION_BY_FH));
        assertTrue(BotNavigationGraphProvider.arcLandsInTargetRegion(rightEdge, 20, REGION_BY_FH));
        assertFalse(BotNavigationGraphProvider.arcLandsInTargetRegion(rightEdge, 14, REGION_BY_FH),
                "the same foothold is not in a different region");
    }

    @Test
    void regionHelperStillMatchesTheProductionRegionLookup() {
        // Guards against the region helper drifting from the builder's own lookup semantics
        // (regionIdByFootholdId.getOrDefault(fh.id, -1) == targetRegionId).
        BotNavigationGraph.Region r = region(20, -114, -66, 952);
        assertTrue(r.id == REGION_BY_FH.get(TARGET_FH.getId()));
    }
}
