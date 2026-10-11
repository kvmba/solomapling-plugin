package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code walkTo}'s stage-walk-shield release policy — the decision has regressed twice, in
 * opposite directions, and had no test either time.
 *
 * <p>The shield's contract (3ff20b0) is that it outlives the WAIT, not the walk: when
 * {@code walkTo} stops waiting at its 1.2s cap the walk keeps flying, and the 250ms combat beat
 * must not re-target it (the puzzle "bots wander off the spot" / "walk yanked at a mob" family) —
 * so the shield stays armed and expires on its own after {@code STAGE_WALK_SHIELD_MS}. The two
 * shipped regressions:
 *
 * <ul>
 *   <li>b2cd04e released the shield in a {@code finally} on EVERY exit — including the deadline
 *       exit with a flying walk — silently re-opening the mid-walk re-targeting the shield was
 *       added to stop;</li>
 *   <li>before that, the interrupt exit released nothing, so an interrupted (or otherwise
 *       abandoned) walk with nothing in flight left an 8s combat-steering blackout.</li>
 * </ul>
 *
 * <p>{@link PqActions#walkShieldReleases} is the single decision point every exit routes through;
 * the table below is that contract in full. The source guard keeps the policy at that seam — a
 * reintroduced unconditional {@code finally}-disarm fails it.
 */
class WalkShieldReleasePolicyTest {

    @Test
    void releasedOnlyWhenNothingIsLeftToShield() {
        assertTrue(PqActions.walkShieldReleases(true, true),
                "arrival releases the shield (the walk landed; the callback cleared the goal)");
        assertFalse(PqActions.walkShieldReleases(false, true),
                "a walk still in flight must KEEP its shield past the wait window (3ff20b0) — "
                        + "the combat beat must not re-target a walk that is still flying");
    }

    @Test
    void engineAbandonedWalkReleases() {
        assertTrue(PqActions.walkShieldReleases(false, false),
                "an engine-abandoned walk has nothing left to shield — releasing avoids a stale "
                        + "blackout (the b2cd04e interrupt complaint)");
        assertTrue(PqActions.walkShieldReleases(true, false),
                "arrival wins regardless of the engine flag");
    }

    @Test
    void walkToReleasesThroughTheSeamAndNotInAFinally() throws Exception {
        String source = javaSource("soloMapling/ArtificialPlayer/PartyQuest/PqActions.java");
        String walkTo = methodBody(source, "public static void walkTo(");
        assertTrue(walkTo.contains("walkShieldReleases("),
                "walkTo must route its shield release through the pinned decision seam");
        assertFalse(walkTo.contains("finally"),
                "walkTo must have no finally block: an unconditional release there is the exact "
                        + "regression (b2cd04e) that re-opened mid-walk re-targeting");
    }

    /** The method body from its signature to the next method-signature line at the same depth. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "signature not found: " + signature);
        int end = source.indexOf("\n    /**", start + signature.length());
        int nextMethod = source.indexOf("\n    public static ", start + signature.length());
        int nextPrivate = source.indexOf("\n    static ", start + signature.length());
        int stop = source.length();
        for (int candidate : new int[]{end, nextMethod, nextPrivate}) {
            if (candidate > start && candidate < stop) {
                stop = candidate;
            }
        }
        return source.substring(start, stop);
    }

    private static String javaSource(String path) throws Exception {
        try (var in = WalkShieldReleasePolicyTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream("src/main/java/" + path)) {
            return new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
