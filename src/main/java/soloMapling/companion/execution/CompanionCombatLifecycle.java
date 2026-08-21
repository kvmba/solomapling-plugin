package soloMapling.companion.execution;

/**
 * Small, engine-neutral lifecycle for one companion training episode.
 */
public final class CompanionCombatLifecycle {

    private boolean active;
    private boolean followingTarget;

    public synchronized void start(Runnable activate) {
        if (!active) {
            activate.run();
            active = true;
        }
    }

    public synchronized void reconcile(
            boolean targetOnline,
            boolean sameMap,
            Runnable deactivate,
            Runnable follow,
            Runnable activate) {
        if (!targetOnline) {
            deactivateIfActive(deactivate);
            followingTarget = false;
            return;
        }
        if (!sameMap) {
            deactivateIfActive(deactivate);
            if (!followingTarget) {
                follow.run();
                followingTarget = true;
            }
            return;
        }
        if (!active) {
            if (!followingTarget) {
                follow.run();
            }
            followingTarget = false;
            activate.run();
            active = true;
        }
    }

    public synchronized void stop(Runnable deactivate) {
        deactivateIfActive(deactivate);
        followingTarget = false;
    }

    public synchronized boolean active() {
        return active;
    }

    private void deactivateIfActive(Runnable deactivate) {
        if (active) {
            active = false;
            deactivate.run();
        }
    }
}
