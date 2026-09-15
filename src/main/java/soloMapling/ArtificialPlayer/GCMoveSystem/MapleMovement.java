package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;

/**
 * The shared, entity-agnostic ground/air movement maths used by BOTH the bot engine
 * and the bot pets — one core instead of two hand-tuned copies. Everything here is a
 * pure function of position/foothold/intent (no Character, no BotMovementState), and
 * the per-step integration matches the client: a "step" is one 8ms ground frame, the
 * way the real client drives walking, so calling code just runs
 * {@link #stepsFor(double)} steps per tick.
 *
 * <p>Units: horizontal speed is <b>px per 8ms step</b> (the client's unit); the caller
 * converts a px/s walk pace with {@link #walkSpeedStep(double)}.</p>
 */
public final class MapleMovement {

    /** The client's ground frame (Physics.img / slide physics run on 8ms steps). */
    public static final double CLIENT_STEP_MS = 8.0;
    private static final double CLIENT_STEP_S = CLIENT_STEP_MS / 1000.0;

    // Client vertical constants (Physics.img), shared by bots and pets: the single
    // source both reference so a jump/fall behaves identically.
    public static final double GRAVITY_PXS2 = 2000.0;
    public static final double MAX_FALL_PXS = 670.0;
    public static final double JUMP_SPEED_PXS = 555.0;
    /** Client base walk pace (BotMovementProfile scales from this by the speed stat). */
    public static final double WALK_VEL_PXS = 125.0;

    // Client ground force/drag model (BotPhysicsEngine cfg, Physics.img).
    private static final double GROUNDSLIP = 3.0;
    private static final double FRICTION = 0.3;
    private static final double SLOPEFACTOR = 0.1;

    // Slippery ground (map fs < 1, e.g. El Nath snow): kinetic linear ramps, not the
    // force/drag model. fs scales both accel and glide; top speed is unchanged.
    public static final double SLIP_WALK_ACCEL = 1400.0;
    public static final double SLIP_GLIDE_DECEL = 400.0;

    private MapleMovement() {
    }

    /** Client ground steps in {@code tickMs} (at least one). */
    public static int stepsFor(double tickMs) {
        return Math.max(1, (int) Math.ceil(tickMs / CLIENT_STEP_MS));
    }

    /** A px/s walk pace as px per 8ms step. */
    public static double walkSpeedStep(double walkPxs) {
        return walkPxs * CLIENT_STEP_S;
    }

    /** The steady-state hforce (px/step) that yields {@code walkPxs} on firm ground. */
    public static double hForceStepForWalkSpeed(double walkPxs) {
        return walkSpeedStep(walkPxs) * (FRICTION + SLOPEFACTOR) / GROUNDSLIP;
    }

    /** The map's ground slipperiness: fs if it is in (0,1), else 1 (firm). */
    public static double slipScale(MapleMap map) {
        float fs = map != null ? map.getFootholdSpeed() : 0.0f;
        return fs > 0.0f && fs < 1.0f ? fs : 1.0;
    }

    /**
     * One 8ms ground step. {@code hspeed}/{@code hForceStep}/{@code walkCapStep} are in
     * px/step; {@code fs} is {@link #slipScale(MapleMap)}. Firm ground uses the client
     * force/drag model (with slope); slippery ground (fs &lt; 1) uses the kinetic ramps.
     * Returns the new hspeed (px/step).
     */
    public static double groundStep(double hspeed, Foothold foothold, int dir,
                                    double hForceStep, double walkCapStep, double fs) {
        if (fs < 1.0) {
            if (dir != 0) {
                double dv = SLIP_WALK_ACCEL * fs * CLIENT_STEP_S * CLIENT_STEP_S;
                return Math.clamp(hspeed + dir * dv, -walkCapStep, walkCapStep);
            }
            double dv = SLIP_GLIDE_DECEL * fs * CLIENT_STEP_S * CLIENT_STEP_S;
            return hspeed - Math.copySign(Math.min(Math.abs(hspeed), dv), hspeed);
        }
        double hforce = dir * hForceStep;
        if (hforce == 0.0 && Math.abs(hspeed) < 0.1) {
            return 0.0;
        }
        double inertia = hspeed / GROUNDSLIP;
        double slope = foothold == null ? 0.0 : Math.clamp(foothold.slope(), -0.5, 0.5);
        double drag = (FRICTION + SLOPEFACTOR * (1.0 + slope * -inertia)) * inertia;
        return hspeed + (hforce - drag) * fs;
    }

    // ── water ────────────────────────────────────────────────────────────────
    // The client's swim model (Physics.img swimSpeed / swimForce): a drag-limited
    // horizontal accel, and a sink under water gravity that an UP-held thrust can
    // overcome — the same law the bots swim with.
    private static final double SWIM_GRAVITY_PXS2 = 590.0;
    private static final double SWIM_UP_THRUST_PXS2 = 412.0;
    private static final double SWIM_DOWN_THRUST_PXS2 = 295.0;
    private static final double SWIM_ACCEL_PXS2 = 600.0;
    private static final double SWIM_FRICTION_HZ = 4.21;
    private static final double SWIM_VEL_PXS = 140.0;
    private static final double SWIM_MAX_SPEED_PXS = 800.0;
    private static final double SWIM_FREE_MAX_SINK_PXS = 140.0;
    private static final double SWIM_DOWN_MAX_SPEED_PXS = 210.0;
    private static final double SWIM_UP_MAX_SINK_PXS = 42.0;

    /** A swim step's outcome (px/s). {@code vy<0} rises; sink caps differ by intent. */
    public record SwimStep(double vx, double vy) {
    }

    /**
     * One swim tick of the client's water model. {@code moveDir} is the horizontal
     * input (-1/0/1); {@code verticalHold} is -1 (hold UP: thrust up), 1 (hold DOWN:
     * extra gravity) or 0 (free: the small no-key sink cap). Speeds are px/s and
     * {@code t} is the tick in seconds.
     */
    public static SwimStep swimStep(double vx, double vy, int moveDir, int verticalHold, double t) {
        if (moveDir != 0) {
            vx += SWIM_ACCEL_PXS2 * t * Integer.signum(moveDir);
        }
        double drag = Math.max(0.0, 1.0 - SWIM_FRICTION_HZ * t);
        vx *= drag;
        vy *= drag;
        vy += SWIM_GRAVITY_PXS2 * t;
        if (verticalHold < 0) {
            vy -= SWIM_UP_THRUST_PXS2 * t;
        } else if (verticalHold > 0) {
            vy += SWIM_DOWN_THRUST_PXS2 * t;
        }
        vx = Math.max(-SWIM_MAX_SPEED_PXS, Math.min(SWIM_MAX_SPEED_PXS, vx));
        if (moveDir != 0) {
            double cap = SWIM_VEL_PXS;
            if (vx > cap && moveDir > 0) {
                vx = cap;
            }
            if (vx < -cap && moveDir < 0) {
                vx = -cap;
            }
        }
        double sinkCap = switch (Integer.signum(verticalHold)) {
            case -1 -> SWIM_UP_MAX_SINK_PXS;
            case 1 -> SWIM_DOWN_MAX_SPEED_PXS;
            default -> SWIM_FREE_MAX_SINK_PXS;
        };
        vy = Math.max(-SWIM_MAX_SPEED_PXS, Math.min(sinkCap, vy));
        return new SwimStep(vx, vy);
    }

    /** The water's UP-held burst (px/s, negative = up) that gets a swimmer rising. */
    public static final double SWIM_JUMP_BURST_PXS = 1000.0;

    /**
     * Lowest y a swimmer may sink to — the client's own map boundary (VR bottom), so
     * it treads water there instead of sinking out of the map. {@code Integer.MAX_VALUE}
     * for maps without usable VR bounds.
     */
    public static int swimFloorY(MapleMap map) {
        java.awt.Rectangle area = map == null ? null : map.getMapArea();
        return area != null && area.height > 0 ? area.y + area.height : Integer.MAX_VALUE;
    }
}
