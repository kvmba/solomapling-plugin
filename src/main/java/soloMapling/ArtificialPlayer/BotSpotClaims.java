package soloMapling.ArtificialPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Generic "spread N bots across spots without overlapping" registry. Not combat-specific: any bot
// system (grinding, town decoration, social grouping, FM spot allocation) can claim a slot on a keyed
// spot (a mapId + a spot id such as a ledge/region id) up to a capacity, get a stable non-overlapping
// X sub-range (section), and release it. Keyed by plain ids + bounds, so it has no dependency on any
// movement / nav internals.
//
// Slots are fixed indices: a holder keeps its slot until it releases, so its section never shifts when
// another holder leaves; a new claimer takes the lowest free slot.
public final class BotSpotClaims {

    private BotSpotClaims() {
    }

    // How many bots may share one town ledge before a pause-point picker should prefer another. Shared
    // by every town consumer (TownStation, TownLoiter, and the occupancy-aware pickers) so the number
    // lives in exactly one place - it used to be a 3 duplicated in both town classes with a "MUST match"
    // comment, which is precisely the kind of drift that lets the hang-out and roaming crowds huddle.
    public static final int TOWN_LEDGE_CAPACITY = 3;

    // mapId -> spotId -> (botId -> slotIndex)
    private static final Map<Integer, Map<Integer, Map<Integer, Integer>>> CLAIMS = new ConcurrentHashMap<>();

    // Claim a slot on (mapId, spotId) if it isn't full. Returns the slot index [0, capacity) the bot
    // holds (its existing one if already claimed), or -1 if the spot is full.
    public static synchronized int claim(int mapId, int spotId, int capacity, int botId) {
        Map<Integer, Map<Integer, Integer>> bySpot = CLAIMS.computeIfAbsent(mapId, k -> new HashMap<>());
        Map<Integer, Integer> slots = bySpot.computeIfAbsent(spotId, k -> new HashMap<>());
        Integer existing = slots.get(botId);
        if (existing != null) {
            return existing;
        }
        int cap = Math.max(1, capacity);
        if (slots.size() >= cap) {
            return -1;
        }
        Set<Integer> used = new HashSet<>(slots.values());
        for (int s = 0; s < cap; s++) {
            if (!used.contains(s)) {
                slots.put(botId, s);
                return s;
            }
        }
        return -1;
    }

    public static synchronized void release(int mapId, int spotId, int botId) {
        Map<Integer, Map<Integer, Integer>> bySpot = CLAIMS.get(mapId);
        if (bySpot == null) {
            return;
        }
        Map<Integer, Integer> slots = bySpot.get(spotId);
        if (slots == null) {
            return;
        }
        slots.remove(botId);
        if (slots.isEmpty()) {
            bySpot.remove(spotId);
        }
        if (bySpot.isEmpty()) {
            CLAIMS.remove(mapId);
        }
    }

    public static synchronized int holders(int mapId, int spotId) {
        Map<Integer, Map<Integer, Integer>> bySpot = CLAIMS.get(mapId);
        if (bySpot == null) {
            return 0;
        }
        Map<Integer, Integer> slots = bySpot.get(spotId);
        return slots == null ? 0 : slots.size();
    }

    // True once a spot holds the full town capacity, i.e. a further bot should be steered to another
    // ledge. A convenience over holders() against TOWN_LEDGE_CAPACITY for the pause-point pickers; a
    // spot nobody holds is never full, so an empty registry reads as wide open.
    public static synchronized boolean isFull(int mapId, int spotId) {
        return holders(mapId, spotId) >= TOWN_LEDGE_CAPACITY;
    }

    // The X sub-range [x0, x1] for slot of a capacity-K spot spanning [minX, maxX]. K=1 = whole span.
    public static int[] section(int minX, int maxX, int slot, int capacity) {
        int k = Math.max(1, capacity);
        int s = Math.max(0, Math.min(slot, k - 1));
        int width = Math.max(0, maxX - minX);
        int x0 = minX + (int) ((long) width * s / k);
        int x1 = minX + (int) ((long) width * (s + 1) / k);
        return new int[]{x0, x1};
    }
}
