package soloMapling.ArtificialPlayer.BotPartySystem;

import org.gms.client.Character;

import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.DebugUtilities.debugprint;

public class BotPartyQueue {

    public static final class PartyInviteEntry {
        private final Character inviter;
        private final int partyId;
        private final long enqueuedAtMs;

        public PartyInviteEntry(Character inviter, int partyId) {
            this(inviter, partyId, System.currentTimeMillis());
        }

        /**
         * Full form, with the queue timestamp supplied. {@link #addPartyInvite} always stamps
         * "now"; this exists so a test can age an entry past {@link #INVITE_TTL_MS} without
         * sleeping through the host's own 3-minute invite window.
         */
        public PartyInviteEntry(Character inviter, int partyId, long enqueuedAtMs) {
            this.inviter = inviter;
            this.partyId = partyId;
            this.enqueuedAtMs = enqueuedAtMs;
        }

        public Character getInviter() {
            return inviter;
        }

        public int getPartyId() {
            return partyId;
        }

        /** When this entry was queued, for the TTL check in {@link #getPartyInvite}. */
        public long enqueuedAtMs() {
            return enqueuedAtMs;
        }
    }

    private final ConcurrentHashMap<Character, PartyInviteEntry> queues;
    private static final BotPartyQueue instance = new BotPartyQueue();

    private BotPartyQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotPartyQueue getInstance() {
        return instance;
    }

    // Last-wins: the entry always mirrors the LATEST invite the engine actually created.
    // Concurrent-invite serialization is already the InviteCoordinator's job (its putIfAbsent
    // refuses a second live invite), so first-wins here only ever preserved STALE entries:
    // the coordinator expires an unanswered invite after ~3 min but this queue never did, and
    // a bot answering with the stale entry's old partyId hit NOT_FOUND at the coordinator -
    // leaving the player's live invite wedged ("taking care of another invitation") for 3 min.
    public synchronized void addPartyInvite(Character fakechar, Character inviter, int partyId) {
        addPartyInvite(fakechar, new PartyInviteEntry(inviter, partyId));
    }

    /**
     * Same as {@link #addPartyInvite(Character, Character, int)}, with the entry supplied whole.
     * Production always passes a freshly stamped entry; the explicit form lets a test age one past
     * the TTL without sleeping through the host's own 3-minute invite window.
     */
    synchronized void addPartyInvite(Character fakechar, PartyInviteEntry entry) {
        debugprint("addPartyInvite: bot=" + fakechar.getName() + ", inviter="
                + entry.getInviter().getName() + ", partyId=" + entry.getPartyId());
        queues.put(fakechar, entry);
        // Wake the bot's macro brain now so pollInvites drains this on the next ~immediate tick,
        // rather than waiting out its slow scheduled cadence while the armed window ticks away.
        BotRecruitManager.wakeBotForInvite(fakechar);
    }

    /**
     * How long a queued invite stays answerable.
     *
     * <p>Matches the host's own expiry: {@code InviteCoordinator.runTimeoutSchedule} drops an
     * unanswered invite after six 30s sweeps (3 minutes). Without a matching TTL here the entry
     * outlived the coordinator's copy, so a bot answering a stale partyId got NOT_FOUND - the
     * player saw "accepted" and no party - while the entry kept authorising the same dead invite
     * on every later turn.
     */
    private static final long INVITE_TTL_MS = 180_000L;

    public synchronized PartyInviteEntry getPartyInvite(Character fakechar) {
        PartyInviteEntry entry = queues.get(fakechar);
        if (entry != null && System.currentTimeMillis() - entry.enqueuedAtMs() > INVITE_TTL_MS) {
            queues.remove(fakechar);
            debugprint("getPartyInvite: expired entry dropped for " + fakechar.getName());
            return null;
        }
        return entry;
    }

    public synchronized boolean hasPendingInvite(Character fakechar) {
        return getPartyInvite(fakechar) != null;
    }

    public synchronized void removePartyInvite(Character fakechar) {
        queues.remove(fakechar);
    }

    public synchronized boolean removePartyInvite(Character fakechar, PartyInviteEntry expected) {
        return queues.remove(fakechar, expected);
    }
}
