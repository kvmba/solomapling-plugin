package soloMapling.companion.routine;

/**
 * The level a companion stops being a novice.
 *
 * <p>Below it, a companion earns nothing it did not fight for: the offline
 * settlement grants no experience at all, and it will not take itself off to
 * a hunting ground. The two rules are the same rule — the beginner island is
 * the tutorial, and a companion should play it rather than be handed levels
 * in its sleep — so they share one bar rather than two that can drift apart.</p>
 *
 * <p>Ten also clears the island's own gate: Sanks asks for level 7, so a
 * companion that has reached ten has been able to leave for a while. Leaving
 * it any lower would let a companion be settled past the point where it could
 * still have taken the boat itself.</p>
 */
public final class CompanionNoviceLevel {

    public static final int VALUE = 10;

    private CompanionNoviceLevel() {
    }

    public static boolean isNovice(int level) {
        return level < VALUE;
    }
}
