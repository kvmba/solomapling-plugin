package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * Locks the fragment layout the client relies on to PLAY a blink instead of jumping the sprite.
 *
 * The bug this guards: a recovery teleport used to go out as one absolute (cmd-0) fragment, so the
 * client had no vanish/reappear to animate and simply moved the sprite in a single frame — the
 * "the bot teleported" glitch. The fix routes those cuts through the blink path, so the byte
 * layout below is now load-bearing for recovery moves too, not just mage blinks.
 *
 * These assert the packet, which is what the client actually reads — a policy test alone would
 * still pass if the renderer silently fell back to a plain position update.
 */
class TeleportCutPathTest {

    @Test
    void emitsThreeFragmentsInDisappearThenAppearOrder() {
        byte[] d = GCMovementSkills.teleportPath(null, new Point(100, 200), new Point(400, 500), 2);
        assertEquals(35, d.length, "1 count byte + 10 + 10 + 14 fragment bytes");
        assertEquals(3, d[0], "numCommands");
        assertEquals(4, d[1], "fragment 1: cmd 4 (vanish at the origin)");
        assertEquals(3, d[11], "fragment 2: cmd 3 (reappear at the destination)");
        assertEquals(0, d[21], "fragment 3: cmd 0 (settle at the destination)");
    }

    @Test
    void carriesOriginThenDestinationCoordinates() {
        byte[] d = GCMovementSkills.teleportPath(null, new Point(100, 200), new Point(400, 500), 2);
        assertEquals(100, s16(d, 2), "fragment 1 x = origin.x");
        assertEquals(200, s16(d, 4), "fragment 1 y = origin.y");
        assertEquals(400, s16(d, 12), "fragment 2 x = dest.x");
        assertEquals(500, s16(d, 14), "fragment 2 y = dest.y");
        assertEquals(400, s16(d, 22), "fragment 3 x settles at dest.x");
        assertEquals(500, s16(d, 24), "fragment 3 y settles at dest.y");
    }

    @Test
    void arrivalFragmentMarksAirborneFootholdZero() {
        // fh 0 on the appear fragment is what tells the client the arrival is airborne, so it runs the
        // reappear animation instead of snapping the sprite onto a foothold.
        byte[] d = GCMovementSkills.teleportPath(null, new Point(0, 0), new Point(300, 0), 2);
        assertEquals(0, s16(d, 16), "appear fragment fh");
    }

    @Test
    void settleFragmentCarriesTheStanceAndTickDuration() {
        byte[] d = GCMovementSkills.teleportPath(null, new Point(0, 0), new Point(300, 0), 7);
        // Fragment 3 starts at index 21; layout is cmd,x,y,velX,velY,fh,stance,duration -> stance at 32.
        assertEquals(7, d[32], "stance byte");
        assertEquals(BotPhysicsEngine.cfg.TICK_MS, s16(d, 33), "duration = one driver tick");
    }

    private static int s16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }
}
