package soloMapling.companion.agent;

/** Pure routing guard used before Dispatcher forwards an unnamed continuation. */
public final class CompanionInteractionPolicy {

    private CompanionInteractionPolicy() {
    }

    public static boolean allowsContinuation(
            boolean sessionMatches,
            boolean playerIsBot,
            boolean playerHasMap,
            int companionMapId,
            int playerMapId) {
        return sessionMatches
                && !playerIsBot
                && playerHasMap
                && companionMapId == playerMapId;
    }
}
