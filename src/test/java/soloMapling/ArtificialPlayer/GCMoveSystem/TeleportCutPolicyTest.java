package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * Characterisation for the recovery-teleport rendering fix: a teleport a player can see must not
 * arrive as a bare single-fragment position jump (the "the bot teleported" glitch). These tests
 * lock the decision, not the rendering, so they need no server.
 */
class TeleportCutPolicyTest {

    @Test
    void unobservedMapEmitsNothing() {
        assertEquals(TeleportCutPolicy.Render.NONE,
                TeleportCutPolicy.choose(false, new Point(100, 100), new Point(900, 100)));
    }

    @Test
    void aFarSnapOnAnObservedMapIsAnimated() {
        // A wedge repair or anchor return easily clears this — that is the visible glitch.
        assertEquals(TeleportCutPolicy.Render.ANIMATED,
                TeleportCutPolicy.choose(true, new Point(100, 100), new Point(900, 100)));
    }

    @Test
    void aVerticalDropIsAnimatedToo() {
        // Falling back onto an anchor several ledges down is just as visible as a horizontal cut.
        assertEquals(TeleportCutPolicy.Render.ANIMATED,
                TeleportCutPolicy.choose(true, new Point(100, 100), new Point(100, 400)));
    }

    @Test
    void aSubFrameSnapIsLeftPlain() {
        assertEquals(TeleportCutPolicy.Render.PLAIN,
                TeleportCutPolicy.choose(true, new Point(100, 100), new Point(110, 104)));
    }

    @Test
    void theThresholdIsExclusive() {
        int d = TeleportCutPolicy.ANIMATE_MIN_PX;
        assertEquals(TeleportCutPolicy.Render.PLAIN,
                TeleportCutPolicy.choose(true, new Point(0, 0), new Point(d, 0)));
        assertEquals(TeleportCutPolicy.Render.ANIMATED,
                TeleportCutPolicy.choose(true, new Point(0, 0), new Point(d + 1, 0)));
    }

    @Test
    void anUnmeasurableCutIsNotAnimatedBlind() {
        assertEquals(TeleportCutPolicy.Render.PLAIN,
                TeleportCutPolicy.choose(true, null, new Point(900, 100)));
        assertEquals(TeleportCutPolicy.Render.PLAIN,
                TeleportCutPolicy.choose(true, new Point(100, 100), null));
    }
}
