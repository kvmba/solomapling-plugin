package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;

// Rope-stall recovery shared by every grind style: while the bot is on a rope, let a deliberate nav
// climb finish, detect a hung climb (no vertical progress), and dismount toward the current target.
// Extracted verbatim from the pre-split GrindBrain; the only change is that the dismount direction
// now reads the brain's shared target (any live target, not the camp-validated sticky one — for a
// dismount kick the direction is all that matters). Ours (SoloMapling).
final class ClimbRecovery {

    private static final long CLIMB_STALL_MS = 1_200;
    private static final int CLIMB_PROGRESS_EPS = 6;
    private static final long DISMOUNT_GRACE_MS = 3_000;

    private final GrindBrain b;
    private long climbStallSinceMs = 0L;
    private long lastDismountMs = 0L;
    private int lastClimbY = 0;

    ClimbRecovery(GrindBrain brain) {
        this.b = brain;
    }

    void reset() {
        climbStallSinceMs = 0L;
        lastDismountMs = 0L;
        lastClimbY = 0;
    }

    // Back on a foothold — clear the stall tracker (called each grounded tick).
    void onGrounded() {
        climbStallSinceMs = 0L;
    }

    // True while the bot is mid-rope or just dismounted — the watchdog defers escalation during recovery.
    boolean isRecovering(Character chr) {
        return GCMovement.isClimbing(chr) || (now() - lastDismountMs) < DISMOUNT_GRACE_MS;
    }

    void handleClimb(Character chr) {
        Point pos = chr.getPosition();
        int y = (pos != null) ? pos.y : 0;
        // A deliberate nav climb (isNavigatingClimb) is left alone ONLY while it actually advances. The
        // old unconditional exemption reset the stall clock on every sample for any bot holding a
        // committed climb edge, so a wedge there hung forever: a rope-top stall (physics clamps to
        // firstClimbableY when no landing resolves), or a mid-rope wriggle that rocks one climb step
        // up and down without net progress. GrindBrain skips the strategy layer while climbing, so the
        // combat heartbeat freezes and the macro watchdog eventually bails the whole map — the bot
        // hangs on the rope and later vanishes from it. Requiring real vertical progress restores the
        // escape hatch: CLIMB_PROGRESS_EPS (6px) sits just ABOVE one climb step (~5px), so a
        // single-step wriggle reads as a stall while any genuine multi-step climb (>= a few px per
        // 250ms sweep) still counts as progress.
        if (climbStallSinceMs == 0L || Math.abs(y - lastClimbY) >= CLIMB_PROGRESS_EPS) {
            climbStallSinceMs = now(); // making progress (or first sample) — let the climb continue
            lastClimbY = y;
            return;
        }
        if (now() - climbStallSinceMs >= CLIMB_STALL_MS) {
            dismountTowardMob(chr, b.currentTargetMonster(chr)); // hung rope -> jump off toward the current target
        }
    }

    private void dismountTowardMob(Character chr, Monster mob) {
        int dx = 0;
        Point pos = chr.getPosition();
        if (mob != null && mob.getPosition() != null && pos != null) {
            dx = Integer.compare(mob.getPosition().x, pos.x);
        }
        GCMovement.dismountRope(chr, dx);
        lastDismountMs = now();
        climbStallSinceMs = 0L;
        b.engaged = false;
        b.lastMoveTargetX = Integer.MIN_VALUE;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
