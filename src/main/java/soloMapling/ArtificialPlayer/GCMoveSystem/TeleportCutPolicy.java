package soloMapling.ArtificialPlayer.GCMoveSystem;

import java.awt.Point;

/*
 * Chooses how a recovery teleport (GCMovement.teleportTo — grind anchor return, wedge repair,
 * companion position repair) is rendered to observers.
 *
 * The problem this exists to solve: the driver's ordinary broadcast sends a single absolute
 * (cmd-0) movement fragment, so a teleport arrives at the client as "the sprite is now over
 * THERE" with nothing in between — a bare snap across the map, the visible "the bot teleported"
 * glitch. The mage blink already owns a client-verified disappear/appear fragment path
 * (GCMovementSkills.broadcastTeleport); recovery snaps just never used it.
 *
 * Pure and side-effect free so it can be tested without a server.
 */
final class TeleportCutPolicy {

    enum Render {
        /* Nobody can see the map — emit nothing (the driver's own broadcast gate handles this too). */
        NONE,
        /* A real jump the observer must see rendered: vanish at the origin, reappear at the destination. */
        ANIMATED,
        /* Close enough that animating is noise — let the driver's plain broadcast carry it. */
        PLAIN
    }

    /*
     * Below this displacement (either axis) the snap lands within a frame or two of ordinary
     * movement; wrapping it in a blink puff would read as twitching. Above it, the client would
     * otherwise jump the sprite with no transition.
     */
    static final int ANIMATE_MIN_PX = 24;

    private TeleportCutPolicy() {
    }

    static Render choose(boolean mapObserved, Point origin, Point dest) {
        if (!mapObserved) {
            return Render.NONE;
        }
        if (origin == null || dest == null) {
            return Render.PLAIN; // can't measure the cut — don't animate blind
        }
        boolean far = Math.abs(dest.x - origin.x) > ANIMATE_MIN_PX
                || Math.abs(dest.y - origin.y) > ANIMATE_MIN_PX;
        return far ? Render.ANIMATED : Render.PLAIN;
    }
}
