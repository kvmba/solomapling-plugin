package soloMapling.companion.agent;

/**
 * Edge-triggered deduplication for party-invite planning turns.
 */
public final class InviteTurnDeduplicator {

    private String plannedKey;

    public synchronized boolean shouldPlan(String inviteKey) {
        if (inviteKey == null || inviteKey.equals(plannedKey)) {
            return false;
        }
        plannedKey = inviteKey;
        return true;
    }

    public synchronized void clear() {
        plannedKey = null;
    }
}
