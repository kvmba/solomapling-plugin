package soloMapling.companion.agent;

/**
 * Pure ordering boundary for CompanionBot's active-only tick work.
 */
public final class CompanionUpdateGate {

    private CompanionUpdateGate() {
    }

    public static boolean runUnlessInactive(boolean inactive, Runnable activeWork) {
        java.util.Objects.requireNonNull(activeWork, "activeWork");
        if (inactive) {
            return false;
        }
        activeWork.run();
        return true;
    }
}
