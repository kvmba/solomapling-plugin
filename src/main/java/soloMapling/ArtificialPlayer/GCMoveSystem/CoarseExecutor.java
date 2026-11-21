package soloMapling.ArtificialPlayer.GCMoveSystem;

import java.awt.Point;

/*
 * Analytic plan executor — the cheap path for unobserved bots. It moves
 * a bot along a MovementPlan by wall-clock time only: no physics integration, no
 * broadcast, no per-tick work. The bot's position is interpolated from the baked edge times on
 * demand.
 *
 * .advance is a pure function of (plan, planStartedAtMs, nowMs) — no hidden
 * state — which makes it deterministic and unit-testable with a fake clock (see
 * CoarseExecutorTest). The mutable "where is this coarse bot" bookkeeping lives on the caller (the
 * driver / a slim coarse record), not here.
 */
final class CoarseExecutor {
    private CoarseExecutor() {
    }

    /* The committed position and surface/rope to restore if this analytic frame becomes visible. */
    record Step(Point position, boolean complete, int regionId) {
    }

    /*
     * Advance plan to wall-clock nowMs (plan started at planStartedAtMs).
     * Returns the interpolated position and whether the trip is finished. Cheap and idempotent — call
     * it on any cadence (a slow wheel, an event wake); the result depends only on elapsed time.
     */
    static Step advance(MovementPlan plan, long planStartedAtMs, long nowMs) {
        long elapsed = Math.max(0L, nowMs - planStartedAtMs);
        if (plan.isComplete(elapsed)) {
            var edge = plan.edges.getLast();
            return new Step(new Point(edge.endPoint), true, edge.toRegionId);
        }
        int index = elapsed == 0 ? 0 : plan.edgeIndexAt(elapsed);
        var edge = plan.edges.get(index);
        return new Step(plan.positionOnEdgeAt(index, elapsed), false, edge.fromRegionId);
    }
}
