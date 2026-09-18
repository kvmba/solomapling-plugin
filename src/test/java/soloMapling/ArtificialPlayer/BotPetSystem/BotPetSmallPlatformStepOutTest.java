package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the small-platform step-out pull-back.
 *
 * <p>{@link BotPetFollower#followTargetX} steps a pet that has been collapsed onto a STANDING owner
 * (the host re-places every pet on the owner at map entry) back out to its comfort ring on its own
 * side — a blind {@code owner ± comfort} x offset. On a platform narrower than the ring that offset
 * falls past the ledge, so the pet walks off the edge, drops, and is warped back beside the owner,
 * then repeats (the reported drop-then-pull-back loop).</p>
 *
 * <p>Clamping the target to the EDGE is not enough: the pet starts the tick at rest but a follow
 * tick is many client ground steps, so it glides {@link BotPetFollower#stepOutEdgeInset} px — past
 * the edge. {@link BotPetFollower#stepOutTargetOnPlatform} therefore pulls the step-out into the
 * platform inset by that margin, and holds in place when the platform is too narrow for its own
 * one-tick travel. These tests pin that band, the narrow-platform hold, and — just as importantly —
 * that every OTHER follow target is left untouched.</p>
 *
 * <p>The map is {@code null} here, so the region probe is a no-op and the single-foothold fallback
 * gives a deterministic ledge span to work against — the rule under test is pure geometry.</p>
 */
class BotPetSmallPlatformStepOutTest {

    // A realistic pet: base walk pace, default 200ms follow tick => inset = ceil(125*0.2)+4 = 29.
    private static final double WALK_PXS = 125.0;
    private static final long TICK_MS = 200L;
    private static final int INSET = BotPetFollower.stepOutEdgeInset(WALK_PXS, TICK_MS);

    private static Foothold ledge(int x1, int x2, int y) {
        return new Foothold(new Point(x1, y), new Point(x2, y), 1);
    }

    private static int stepOut(int targetX, int ownerX, int petX, Foothold fh) {
        return BotPetFollower.stepOutTargetOnPlatform(null, targetX, ownerX, new Point(petX, 500), fh,
                WALK_PXS, TICK_MS);
    }

    @Test
    void insetHoldsAWholeTicksWalkPlusArriveBand() {
        assertTrue(INSET >= 25 + 4,
                "inset must cover the pet's own one-tick travel (125px/s x 0.2s) plus the arrive band");
        // It grows with both pace and tick length.
        assertTrue(BotPetFollower.stepOutEdgeInset(250.0, TICK_MS) > INSET, "faster pace => larger inset");
        assertTrue(BotPetFollower.stepOutEdgeInset(WALK_PXS, 400) > INSET, "longer tick => larger inset");
    }

    @Test
    void airRingIsPulledBackInsideTheSafeBand() {
        // Pet collapsed onto the owner's pixel (x=100); the step-out ring is owner + comfort = 140.
        int ring = BotPetFollower.followTargetX(100, 100, 40, false);
        assertEquals(140, ring, "the leash steps a collapsed pet out to owner + comfort");
        // The ledge reaches x=150, so the safe band stops at 150 - INSET (=121): pull 140 back to it.
        assertEquals(150 - INSET, stepOut(ring, 100, 100, ledge(80, 150, 500)),
                "an overhanging ring is pulled to the safe band inside the platform edge");
    }

    @Test
    void leftwardStepOutIsPulledBackOnTheLeftEdge() {
        // Pet a hair LEFT of the owner, so the ring resolves to the left (100 - 40 = 60).
        int ring = BotPetFollower.followTargetX(100, 99, 40, false);
        assertEquals(60, ring, "the ring lands to the left of the owner");
        // The ledge reaches x=50, so the safe band starts at 50 + INSET (=79): pull 60 back to it.
        assertEquals(50 + INSET, stepOut(ring, 100, 99, ledge(50, 400, 500)),
                "the leftward pull mirrors the rightward one");
    }

    @Test
    void aRingAlreadyInTheSafeBandIsUntouched() {
        // Wide platform: the ring (125) is well inside [80+INSET, 200-INSET] => unchanged.
        int ring = BotPetFollower.followTargetX(100, 100, 25, false);
        assertEquals(ring, stepOut(ring, 100, 100, ledge(80, 200, 500)));
    }

    @Test
    void aPlatformNarrowerThanOneTickTravelHoldsThePet() {
        // Ledge only 30px wide; the safe edge (110 - INSET) is behind the pet, so it must hold.
        int ring = BotPetFollower.followTargetX(100, 100, 40, false);
        assertEquals(100, stepOut(ring, 100, 100, ledge(80, 110, 500)),
                "a platform too narrow for a safe step-out keeps the pet where it stands");
    }

    @Test
    void aPetAlreadyOutsideTheSafeBandIsNeverPulledInward() {
        // A pet already past the safe-band edge (narrow ledge, pet near the edge) must NOT be walked
        // BACK toward the owner — that would read as the leash reversing. It holds instead.
        int ring = BotPetFollower.followTargetX(120, 120, 40, false); // ring = 160
        int safeHi = 125 - INSET;
        assertTrue(120 > safeHi, "precondition: the pet already sits past the safe band");
        assertEquals(120, stepOut(ring, 120, 120, ledge(80, 125, 500)),
                "a pet already outside the safe band holds, never pulled inward");
    }

    @Test
    void holdAndLeashCloseTargetsAreNeverTouched() {
        // Hold target: inside the leash the target IS the pet's own x (delta 0) — not a step-out.
        assertEquals(40, stepOut(40, 100, 40, ledge(10, 90, 500)),
                "a hold target is not steered, so it is never pulled");
        // Leash-close: pet drawn out to the RIGHT (x=200), target sits BETWEEN owner and pet (140) on
        // the reverse side — even though 140 is outside this ledge, it must stay put.
        int close = BotPetFollower.followTargetX(100, 200, 40, false);
        assertEquals(140, close, "a drawn-out pet closes to the comfort ring between it and the owner");
        assertEquals(140, stepOut(close, 100, 200, ledge(150, 400, 500)),
                "a leash-close target heads back toward the owner — never pulled");
    }

    @Test
    void noStandablePlatformLeavesTheTargetAlone() {
        int ring = BotPetFollower.followTargetX(100, 100, 40, false);
        // No foothold at all (the pet is over a gap): nothing to bound the step-out against.
        assertEquals(ring, BotPetFollower.stepOutTargetOnPlatform(null, ring, 100, new Point(100, 500),
                null, WALK_PXS, TICK_MS));
    }
}
