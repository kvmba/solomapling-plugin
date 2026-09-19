package soloMapling.ArtificialPlayer.BotDetailSystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Deterministic roll helpers shared by the detail-window presets.
 *
 * <p>Every value a bot shows in its character-info window is derived from its character id
 * (plus a per-dataset salt) rather than stored, so a persistent companion shows the same
 * collection across restarts while an ambient bot never touches the database. This mirrors
 * {@code BotMountSystem.BotMount.mix(int)} - same cheap integer hash, same "stable per cid"
 * contract - but keeps its own copy so this feature has no cross-package dependency.
 */
final class BotDetailRoll {

    private BotDetailRoll() {
    }

    /** A cheap deterministic integer hash of {@code cid} and a per-dataset {@code salt}. */
    static int mix(int cid, int salt) {
        int h = (cid ^ salt) * 0x9E3779B1;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }

    /**
     * Pick {@code k} distinct elements from {@code pool} in a stable pseudo-random order.
     * {@code java.util.Random} has a specified LCG, so the same {@code (cid, salt, pool)}
     * always yields the same pick across JVMs and restarts. Returns a fresh, mutable list of at
     * most {@code min(k, pool.size())} elements; empty when {@code k <= 0} or the pool is empty.
     */
    static <T> List<T> sample(int cid, int salt, List<T> pool, int k) {
        List<T> copy = new ArrayList<>(pool);
        if (k <= 0 || copy.isEmpty()) {
            return new ArrayList<>();
        }
        Collections.shuffle(copy, new Random(mix(cid, salt)));
        return new ArrayList<>(copy.subList(0, Math.min(k, copy.size())));
    }
}
