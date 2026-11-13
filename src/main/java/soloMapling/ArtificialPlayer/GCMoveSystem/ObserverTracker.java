package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Observability tracker for the GCMoveSystem LOD layer. A single ~1s poll over the online real
 * players maintains two sets of live map instances, isolated by world/channel/event:
 * 
 *   fullMaps — a real player is on the map (FULL tier: physics + broadcast).
 *   haloMaps — a portal-adjacent map a real player is standing near the entrance to (HALO: pre-warm
 *              physics on the map they're about to walk into).
 *
 *
 * The driver/scheduler reads .isActiveMap(MapleMap) to decide whether a bot's map is worth full
 * fidelity. Polling (rather than hooking MapleMap.addPlayer/removePlayer) keeps this entirely
 * additive — no Cosmic base file is touched. Halo is proximity-gated: a neighbour is warmed only while
 * a player is within HALO_PORTAL_RADIUS_PX of the portal leading to it — this bounds hub fan-out (a
 * player mid-town near no portal warms nothing) and pre-warms the map being approached. It uses
 * GCWorldGraph's raw portal adjacency to filter (not route(), which adds non-adjacent taxi edges) and
 * never forces the world graph to build — halo stays empty until that graph exists via some other path.
 * Anything not pre-warmed in time still snaps to FULL instantly on actual entry (BotMapEntryResponder).
 */
final class ObserverTracker {
    private ObserverTracker() {
    }

    private static final long POLL_MS = 1000;
    // Hysteresis (M3): a map keeps full fidelity for this long after losing its last real player, so a
    // player pacing across a portal can't flap bots between physics and coarse.
    // Promotion is immediate; only demotion waits out the dwell ("err toward FULL — over-render is safe").
    private static final long DWELL_MS = 4000;

    // HALO proximity gate: a neighbour map is pre-warmed only while a real player is within this many
    // pixels of the portal that leads to it — about the lead distance for the ~1s poll + bot promotion
    // to finish before the player crosses. A player mid-town (near no portal) warms nothing. Tune live.
    private static final int HALO_PORTAL_RADIUS_PX = 350;
    private static final double HALO_PORTAL_RADIUS_SQ =
            (double) HALO_PORTAL_RADIUS_PX * HALO_PORTAL_RADIUS_PX;

    // Map identity isolates channels, worlds and event instances without allocating keys on hot reads.
    private static volatile Set<MapleMap> fullMaps = Set.of();
    private static volatile Set<MapleMap> haloMaps = Set.of();

    // The sticky "active" set (full ∪ halo, held for DWELL_MS after observation) — the dwell hysteresis.
    private static final TierDwell<MapleMap> DWELL = new TierDwell<>(DWELL_MS);

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    // Transient promotion override: when a bot ENTERS a map that already has a real player, we mark
    // that map observed immediately so FULL behaviour (visible spawn, broadcast, combat) starts the
    // same tick instead of waiting up to one poll. Short TTL (> POLL_MS) so the poll takes back over.
    private static final long FORCE_TTL_MS = 2_000;
    private static final Map<MapleMap, Long> forcedFull = new ConcurrentHashMap<>();

    // A real (non-bot) player is on this map right now — a live check, not the cached poll.
    static boolean hasRealPlayerNow(MapleMap map) {
        if (map == null) {
            return false;
        }
        for (Character c : map.getAllPlayers()) {
            if (c != null && !BotHelpers.isBot(c)) {
                return true;
            }
        }
        return false;
    }

    // Treat this map instance as FULL immediately, regardless of the 1s poll.
    static void markObservedNow(MapleMap map) {
        forcedFull.put(map, MovementClock.nowMs() + FORCE_TTL_MS);
    }

    private static boolean forced(MapleMap map) {
        Long until = forcedFull.get(map);
        if (until == null) {
            return false;
        }
        if (until < MovementClock.nowMs()) {
            forcedFull.remove(map, until);
            return false;
        }
        return true;
    }

    /* Start the poll once (idempotent). Called from GCMovement.enable(Character). */
    static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gcmove-observer");
            t.setDaemon(true);
            return t;
        });
        pool.scheduleAtFixedRate(ObserverTracker::safeRefresh, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /* Whether the observer poll is running (started by the first GCMovement.enable). */
    static boolean started() {
        return STARTED.get();
    }

    /* A real player is on this map (FULL tier). */
    static boolean isFull(MapleMap map) {
        return map != null && (fullMaps.contains(map) || forced(map));
    }

    /* A real player is standing near the portal leading to this map (HALO tier). */
    static boolean isHalo(MapleMap map) {
        return map != null && haloMaps.contains(map);
    }

    /*
     * The map is worth full-fidelity physics (and is exempt from LOD throttling/coarse): FULL or HALO
     * now, OR within the post-observation dwell window. This is the set the driver gates on.
     */
    static boolean isActiveMap(MapleMap map) {
        return map != null && (DWELL.isActive(map) || forced(map));
    }

    static int fullCount() {
        return fullMaps.size();
    }

    static int haloCount() {
        return haloMaps.size();
    }

    /* Maps held at full fidelity right now (FULL ∪ HALO ∪ dwelling). */
    static int activeCount() {
        return DWELL.size();
    }

    /* Snapshot of the maps currently FULL (a real player present) — diagnostics/inspection only. */
    static Set<Integer> fullMaps() {
        return mapIds(fullMaps);
    }

    /* Snapshot of the maps currently HALO (portal-adjacent to a real player) — diagnostics/inspection only. */
    static Set<Integer> haloMaps() {
        return mapIds(haloMaps);
    }

    private static Set<Integer> mapIds(Set<MapleMap> maps) {
        Set<Integer> ids = new HashSet<>();
        for (MapleMap map : maps) ids.add(map.getId());
        return Set.copyOf(ids);
    }

    private static void safeRefresh() {
        try {
            refresh();
        } catch (Throwable ignored) {
            // a thrown poll would cancel the periodic task — keep the last-known sets
        }
    }

    private static void refresh() {
        Server server = Server.getInstance();
        if (server == null) {
            return;
        }
        Set<MapleMap> full = new HashSet<>();
        List<Character> realPlayers = new ArrayList<>();
        for (World world : server.getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (chr != null && chr.getMap() != null && !BotHelpers.isBot(chr)) {
                    full.add(chr.getMap());
                    realPlayers.add(chr);
                }
            }
        }

        // HALO is proximity-gated: warm a neighbour only while a player is standing near the portal that
        // leads to it, instead of warming every neighbour of an occupied map (which warms a whole region
        // from a hub town). Only fill from an already-built world graph — never force the ~5k-map scan.
        Set<MapleMap> halo = new HashSet<>();
        if (GCWorldGraph.isReady()) {
            for (Character chr : realPlayers) {
                addNearbyPortalMaps(chr, full, halo);
            }
        }

        observe(full, halo, MovementClock.nowMs());
    }

    // Single poll writer; explicit time also keeps promotion/demotion tests deterministic.
    static void observe(Set<MapleMap> full, Set<MapleMap> halo, long now) {
        Set<MapleMap> previous = DWELL.active();
        Set<MapleMap> observed = new HashSet<>(full);
        observed.addAll(halo);
        DWELL.observe(observed, now);
        forcedFull.entrySet().removeIf(e -> e.getValue() < now);

        fullMaps = full.isEmpty() ? Set.of() : Set.copyOf(full);
        haloMaps = halo.isEmpty() ? Set.of() : Set.copyOf(halo);
        for (MapleMap map : observed) {
            if (previous.contains(map)) continue;
            for (Character chr : map.getAllPlayers()) {
                if (BotHelpers.isBot(chr)) GCMovement.wake(chr);
            }
        }
    }

    // Add to `halo` the walkable target maps of any portal on the player's map within
    // HALO_PORTAL_RADIUS of the player. Intersecting each portal's target with GCWorldGraph's portal
    // neighbours keeps the filtering (doors / scripted / version-gated / no-target portals excluded)
    // consistent with the rest of the LOD layer, and gives the portal positions the graph doesn't carry.
    private static void addNearbyPortalMaps(Character chr, Set<MapleMap> full, Set<MapleMap> halo) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return;
        }
        int[] walkable = GCWorldGraph.portalNeighbors(chr.getMapId());
        if (walkable.length == 0) {
            return;
        }
        Point pos = chr.getPosition();
        if (pos == null) {
            return;
        }
        MapManager maps = map.getEventInstance() == null
                ? map.getChannelServer().getMapFactory() : map.getEventInstance().getMapFactory();
        for (Portal portal : map.getPortals()) {
            if (portal == null) {
                continue;
            }
            int target = portal.getTargetMapId();
            if (target <= 0 || !contains(walkable, target)) {
                continue;
            }
            Point pp = portal.getPosition();
            if (pp != null && pos.distanceSq(pp) <= HALO_PORTAL_RADIUS_SQ && maps.isMapLoaded(target)) {
                MapleMap neighbor = maps.getMap(target);
                if (!full.contains(neighbor)) halo.add(neighbor);
            }
        }
    }

    private static boolean contains(int[] arr, int value) {
        for (int x : arr) {
            if (x == value) {
                return true;
            }
        }
        return false;
    }
}
