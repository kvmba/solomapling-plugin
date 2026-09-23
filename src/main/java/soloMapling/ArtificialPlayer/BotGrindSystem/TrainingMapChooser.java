package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.client.Character;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// WHICH map a training bot grinds: the whole selection cluster extracted from TrainingBot — the
// two-sided level band with its noob floor, chill visits, downward-only deep-hub floors, the
// level-scaled wander radius, the fit/distance/capacity weighting, and the world-wide occupancy
// registry with its slot-reservation race loop. TrainingBot's doDecide keeps only the FSM
// choreography (station-here, party targets, first-trip warp) and calls choose(). Ours (SoloMapling).
public final class TrainingMapChooser {

    // ── Admission band ──
    private static final int LEVEL_BAND = 12;                // full-weight comfort band around the bot's level
    // Two-sided admission: a map's mob level must be within [minMobFor(level), level + LEVEL_BAND]. The
    // lower edge is a CONSTANT look-down span, not a fraction, so a noob floors at mob-level 1: a level<=25
    // admits everything down to lvl-1 (snails right outside town — the lifeblood of the low-level areas)
    // AND up to level+12, while a level-80's floor sits at 55 so pros stay off trivial mobs. Levels are
    // only a small piece — near/far + crowd (distanceWeight, capacity) do the fine selection in the band.
    private static final int LOWER_SPAN = 25;
    // Below the comfort floor, a map's pick-weight decays with how far under it sits (belt-and-suspenders
    // with the admission band) — this only bites the fallback edge, where nothing was in-band at all.
    private static final int LEVEL_FIT_DECAY = 10;           // gap-decay scale (levels below comfort per e-fold)
    // Downward-only "pro" deep hubs (deep Ludibrium): the cohort should only ever migrate DEEPER (harder
    // mobs), never back up toward town. Enforced by tightening the admission floor to (level - this span)
    // — which the finder's FALLBACK now also respects — and by suppressing chill visits. See DeepHub.
    private static final int DOWNWARD_HUB_LOWER_SPAN = 10;

    // ── Chill visits (intentional, rare social variety) ──
    private static final double CHILL_VISIT_CHANCE = 0.06;
    private static final int CHILL_MIN_LEVEL = 30;           // below this there's nothing to "chill down" to
    private static final int CHILL_REACH = 3;                // chill visits stay near town (few hops out)
    private static final int CHILL_SPREAD = 25;              // how far below the comfort band a chill map may reach

    // ── Wander radius (hops from town) ──
    private static final int[][] HOPS_BY_LEVEL = {           // {level, hops}, linearly interpolated between points
            {1, 1}, {15, 2}, {30, 4}, {50, 8}, {70, 10}
    };
    private static final int ANYWHERE_LEVEL = 70;            // 70+ : open the radius to the whole landmass
    // "anywhere" radius — how far from town a max-level bot may discover a hunting ground. 20 was set
    // when a bot's continent was assumed to end at the coast; on the live world graph the walkable
    // portal path joins a whole continent end to end, and its far content sits well past 20. Ludibrium
    // town -> 怪兽地区 (221030601) is 57 hops — the only route climbs the entire 玩具塔 one floor at a
    // time (221020000 -> ... -> 221024400) — and Orbis -> its far fields is 44. At 20 a high bot simply
    // never saw the far half of the continent it stands on. 64 clears the deepest such span with
    // headroom, and matches GCTravel.MAX_HOPS so anything discoverable is routable.
    //
    // NOTE this radius deliberately rides the PORTAL-ONLY graph (see GCMovement.mapsWithinHopsByDepth,
    // which excludes taxi/ferry hops). Crossing to another continent is the job of migration
    // (TrainingRegions), not the discovery radius: folding ferry edges in here would hand every bot the
    // whole ~550-map world as candidates at once (measured) and let town wanderers drift between towns.
    private static final int MAX_HOPS = 64;

    // ── Distance bias (fresh bots hug town, high bots venture to the edge of their reach) ──
    private static final int VENTURE_FULL_LEVEL = 50;        // level by which a bot fully prefers far maps
    private static final double DISTANCE_WEIGHT_BASE = 0.15; // floor so a non-preferred-distance map is still possible

    private static final int MAX_REDECIDES = 2;              // capacity-reservation re-rolls before accepting an over-cap map

    // ── Roamer mode (the "low-level wanderer" bot) ──
    // A RoamerBot hunts LOW-level monsters on purpose and roams freely, so its admission band and reach
    // are the OPPOSITE of a TrainingBot's: no upward comfort band, a hard low ceiling, a bias toward the
    // LOWEST maps (not the same-level ones), the whole connected landmass always in reach (no level-scaled
    // radius), and no distance bias (it wanders the map, it does not hug town or push outward).
    private static final int ROAMER_MAX_MOB_LEVEL = 60;      // never hunts a mob above this — "low-level area"
    private static final int ROAMER_MOB_DECAY = 20;          // per e-fold preference for the lowest maps (soft, so it spreads)
    private static final int ROAMER_MOB_FLOOR = 1;           // huntable view floors the median at 1 anyway

    // Which occupancy registry an owner reserves into. TRAINING is the historical behaviour (every
    // existing caller); ROAMER is a SEPARATE table so a low-level RoamerBot never consumes a slot a
    // TrainingBot could have used (and vice versa) — the "independent quota" the roamer design asks for.
    // The physical layer still de-duplicates: SpotFinder/BotSpotClaims cap claims per spot and per map,
    // so two registries that both think a low map has room cannot actually stack bots on it — an
    // over-subscribed side simply reads mapSaturated and crowd-bails to the next map.
    public enum Scope { TRAINING, ROAMER }

    // How many bots of each scope currently target each map (world-wide). DECIDE reserves a slot here
    // BEFORE travelling, so simultaneous deciders see each other and spread across maps.
    private static final Map<Scope, Map<Integer, AtomicInteger>> BOTS_PER_MAP = new ConcurrentHashMap<>();

    private TrainingMapChooser() {
    }

    // Historical entry point: the TrainingBot path, unchanged. Same band, radius, weighting, scope.
    public static TrainingMap choose(Character chr, int homeMapId, Set<Integer> excluded, Consumer<String> debug) {
        return choose(chr, homeMapId, excluded, debug, false);
    }

    // Pick a level-appropriate, uncrowded map for this bot and RESERVE an occupancy slot on it — the
    // returned pick's slot is HELD (the caller records it as its train target and releases it via
    // release() when done). weightedPick hard-caps maps at capacity, and the reservation loop closes
    // the cohort race: if our atomic increment pushed the map OVER capacity (another bot grabbed the
    // last slot at the same instant), release and re-pick — the bumped count now hard-zeros that map,
    // steering us deeper. Null = nothing reachable with mobs (caller idles in town and retries later).
    //
    // `roamer` flips the whole selection to the low-level wanderer profile (see ROAMER_* above) and
    // switches the occupancy scope to ROAMER. The default overload passes false, so every existing
    // caller is byte-for-byte the old behaviour.
    public static TrainingMap choose(Character chr, int homeMapId, Set<Integer> excluded, Consumer<String> debug,
                                     boolean roamer) {
        Scope scope = roamer ? Scope.ROAMER : Scope.TRAINING;
        int level = chr.getLevel();
        int reach = roamer ? MAX_HOPS : hopsForLevel(level);
        // Deep-hub grind: a cohort that spawns on a mob-bearing "deep hub" field may grind the hub
        // itself — the map BFS drops its own origin, so admit the current map explicitly when the bot
        // stands on its home hub and that hub has mobs.
        boolean includeOrigin = chr.getMapId() == homeMapId && MapMobIndex.level(homeMapId) >= 0;
        DeepHub.Info hub = DeepHub.of(homeMapId);
        boolean downwardOnly = !roamer && hub != null && hub.downwardOnly();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Roamer: a fixed low band [1, ROAMER_MAX_MOB_LEVEL], the whole landmass in reach, no chill, no
        // downward-only floor. The band's ceiling — not the bot's level — is what makes it hunt LOW mobs.
        // The training path below is unchanged from before the scope split (including its chill fallback).
        boolean chill = false;
        int minMob;
        int maxMob;
        int useReach;
        int hardMinMob = 1;
        if (roamer) {
            minMob = ROAMER_MOB_FLOOR;
            maxMob = ROAMER_MAX_MOB_LEVEL;
            useReach = MAX_HOPS; // a roamer always sees the whole connected landmass
        } else {
            // Normal trip: the two-sided band. Rarely, a mid/high bot takes a "chill" trip to an easy
            // NEAR-TOWN map — a strictly below-comfort band capped near town; nothing to chill at -> retry
            // as a normal trip. Downward-only pros never chill (a chill is an up-trip by construction).
            chill = !downwardOnly && level >= CHILL_MIN_LEVEL && rng.nextDouble() < CHILL_VISIT_CHANCE;
            minMob = chill ? Math.max(1, level - LEVEL_BAND - 1 - CHILL_SPREAD) : minMobFor(level);
            maxMob = chill ? Math.max(1, level - LEVEL_BAND - 1) : level + LEVEL_BAND;
            useReach = chill ? Math.min(reach, CHILL_REACH) : reach;
            if (downwardOnly) {
                minMob = Math.max(minMob, level - DOWNWARD_HUB_LOWER_SPAN);
                hardMinMob = minMob; // the finder's fallback respects this too — a pro is never offered an easy up-map
            }
        }

        for (int attempt = 0; attempt <= MAX_REDECIDES; attempt++) {
            List<TrainingMap> eligible = TrainingMapFinder.findTrainingMaps(
                    chr.getMapId(), level, minMob, maxMob, useReach, excluded, includeOrigin, hardMinMob);
            if (roamer) {
                // The finder falls back to the closest-level maps when nothing is in-band, and for a
                // roamer "closest to level" means a HIGH map — exactly what it must never hunt. Keep only
                // the low ones; nothing left means idle in town (and the town beat re-decides).
                final int ceiling = maxMob; // roamer never reassigns maxMob, so this is the ROAMER_MAX_MOB_LEVEL band cap
                eligible = new java.util.ArrayList<>(eligible.stream()
                        .filter(m -> m.mobLevel() <= ceiling).toList());
            } else if (eligible.isEmpty() && chill) {
                chill = false; // nothing to chill at -> retry as a normal trip (unchanged training behaviour)
                minMob = minMobFor(level);
                maxMob = level + LEVEL_BAND;
                useReach = reach;
                eligible = TrainingMapFinder.findTrainingMaps(
                        chr.getMapId(), level, minMob, maxMob, useReach, excluded, includeOrigin, hardMinMob);
            }
            if (eligible.isEmpty()) {
                return null; // nothing reachable with mobs (or all on cooldown)
            }
            TrainingMap candidate = weightedPick(eligible, level, useReach, roamer);
            reserve(scope, candidate.mapId());
            if (botsOnMap(scope, candidate.mapId()) <= mapCapacity(candidate.mapId()) || attempt == MAX_REDECIDES) {
                return candidate; // within capacity, or out of re-rolls -> accept (saturated region: share)
            }
            release(scope, candidate.mapId());
            debug.accept("DECIDE: map " + candidate.mapId() + " over cap, re-picking");
        }
        return null; // not reached — the loop always returns on its last attempt
    }

    // ── Continental migration support ──

    // Whether a level-appropriate hunting ground is reachable around `fromMapId` right now — the same
    // two-sided band and level-scaled radius DECIDE uses (and the deep-hub downward floor), and NO
    // occupancy reservation (asking must not disturb the slot registry). This is the "has this bot
    // outgrown its continent?" probe a migration needs, because a continent's entry BAR cannot answer
    // it: the bar is the level a bot must reach to COME HERE, not a statement that everything here still
    // fits the level it has since reached. Ludibrium's bar is 30, but it holds fields for a 40-54 cohort
    // — so a 40-54 bot there has not outgrown it, however many harder continents its level cleared.
    //
    // It must band-check the finder's RESULT, not merely ask whether the result is empty:
    // findTrainingMaps falls back to the closest-level maps when nothing is in band, so a non-empty list
    // only means "some mob map is reachable", which is true almost everywhere. The finder returns the
    // in-band maps when any exist and only the out-of-band fallback otherwise, so a band test separates
    // the two — using emptiness would read every bot as "still has targets" and switch the climb off.
    public static boolean hasInBandTarget(int fromMapId, int level) {
        int minMob = minMobFor(level);
        int maxMob = level + LEVEL_BAND;
        // Mirror DECIDE's downward-only floor: a "pro" hub whose cohort only trains DEEPER must not read
        // a trivial up-map as "still has something".
        DeepHub.Info hub = DeepHub.of(fromMapId);
        if (hub != null && hub.downwardOnly()) {
            minMob = Math.max(minMob, level - DOWNWARD_HUB_LOWER_SPAN);
        }
        boolean includeOrigin = MapMobIndex.level(fromMapId) >= 0;
        for (TrainingMap m : TrainingMapFinder.findTrainingMaps(
                fromMapId, level, minMob, maxMob, hopsForLevel(level), Set.of(), includeOrigin)) {
            if (m.mobLevel() >= minMob && m.mobLevel() <= maxMob) {
                return true; // an in-band map survived admission
            }
        }
        return false;
    }

    // The climb rule, pure so it can be reasoned about (and pinned) without a live world: a bot is
    // FORCED off its continent only when it has genuinely outgrown it (nothing left in band) and is
    // still below the free-move level. Qualifying to move is not the same as having to.
    public static boolean mustLeaveContinent(int level, boolean continentStillHasTargets) {
        return level < TrainingRegions.FREE_MOVE_LEVEL && !continentStillHasTargets;
    }

    // Whether a below-free-move bot MUST cross right now. The two call sites (the training bot and the
    // companion) share this one statement of the rule so they cannot drift apart — an inverted guard in
    // only one of them is exactly the kind of silent divergence this removes.
    //
    // Two cases force a crossing, and they are DIFFERENT:
    //   - the beginner island's one-way boat — an EXIT a bot must take, never a climb to gate on
    //     content (the island holds snails and such, so a content test would strand every bot there);
    //   - a real continent genuinely outgrown (nothing left in band to fight).
    // Everything else is optional: a free mover rolls the dice, and a bot with something left to fight
    // stays put.
    public static boolean forcedCrossing(int homeMapId, int level) {
        if (TrainingRegions.isBeginnerIsland(homeMapId)) {
            return true; // the one-way boat off 彩虹岛 — an exit, not an outgrown continent
        }
        return mustLeaveContinent(level, hasInBandTarget(homeMapId, level));
    }

    // ── Occupancy registry ──
    //
    // Two separate tables (see Scope). The no-arg forms are the historical TrainingBot/companion API and
    // always address the TRAINING table, so those callers are unchanged; the roamer addresses ROAMER.
    // Reserve and release MUST use the same scope for a given map, so a bot records its scope with the
    // target it reserved (TrainingBot/SoloGrindController pass TRAINING; RoamerBot passes ROAMER).

    public static void reserve(int mapId) {
        reserve(Scope.TRAINING, mapId);
    }

    public static void release(int mapId) {
        release(Scope.TRAINING, mapId);
    }

    public static int botsOnMap(int mapId) {
        return botsOnMap(Scope.TRAINING, mapId);
    }

    public static void reserve(Scope scope, int mapId) {
        table(scope).computeIfAbsent(mapId, k -> new AtomicInteger()).incrementAndGet();
    }

    public static void release(Scope scope, int mapId) {
        AtomicInteger c = table(scope).get(mapId);
        if (c != null) {
            c.decrementAndGet();
        }
    }

    public static int botsOnMap(Scope scope, int mapId) {
        AtomicInteger c = table(scope).get(mapId);
        return c == null ? 0 : Math.max(0, c.get());
    }

    private static Map<Integer, AtomicInteger> table(Scope scope) {
        return BOTS_PER_MAP.computeIfAbsent(scope, k -> new ConcurrentHashMap<>());
    }

    // Read-only occupancy peek for the !env grindprofile dump (watch distribution live).
    public static int botsTargeting(int mapId) {
        return botsOnMap(Scope.TRAINING, mapId);
    }

    // A map's bot carrying capacity = its claimable-spot count (or the span quota on a ROAM map).
    // SpotFinder prefers the exact live profile when the map has one, else a cached cluster-count
    // estimate over the static WZ spawn positions — no live map load, no nav bake.
    public static int mapCapacity(int mapId) {
        return SpotFinder.mapBotCapacity(mapId);
    }

    // ── Selection math ──

    // Wander radius (hops from town) for a level, interpolating HOPS_BY_LEVEL between its breakpoints.
    // ANYWHERE_LEVEL and up opens the radius to MAX_HOPS (the whole connected landmass). Low bots hug
    // town, 30+ bots range widely (Victoria's good maps are far out), 70+ can go essentially anywhere.
    static int hopsForLevel(int level) {
        if (level >= ANYWHERE_LEVEL) {
            return MAX_HOPS;
        }
        int[][] bp = HOPS_BY_LEVEL;
        if (level <= bp[0][0]) {
            return bp[0][1];
        }
        for (int i = 1; i < bp.length; i++) {
            if (level <= bp[i][0]) {
                int loL = bp[i - 1][0], hiL = bp[i][0];
                int loH = bp[i - 1][1], hiH = bp[i][1];
                return (int) Math.round(loH + (hiH - loH) * (double) (level - loL) / (hiL - loL));
            }
        }
        return MAX_HOPS; // above the top breakpoint but below ANYWHERE_LEVEL — safety only
    }

    // Weighted random over eligible maps: level-appropriate maps full weight, easier maps a reduced chance,
    // biased by distance (low bots near town, high bots further out), and CAPACITY-AWARE — a map at/over
    // its carrying capacity is hard-zeroed so it drops out of contention and selection spills to deeper,
    // less-crowded maps; the rest are weighted by remaining headroom. If every reachable map is full,
    // falls back to a soft divisor so the bot still goes somewhere (and shares) rather than idling.
    //
    // `roamer` swaps fit + distance for the low-level preference: weight by the LOWEST mob level (soft
    // decay) and no distance bias, and read the ROAMER occupancy table.
    private static TrainingMap weightedPick(List<TrainingMap> maps, int level, int reach, boolean roamer) {
        Scope scope = roamer ? Scope.ROAMER : Scope.TRAINING;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double[] weights = new double[maps.size()];
        double total = 0;
        for (int i = 0; i < maps.size(); i++) {
            TrainingMap m = maps.get(i);
            int cap = mapCapacity(m.mapId());
            int n = botsOnMap(scope, m.mapId());
            double base = roamer
                    ? roamerFitWeight(m.mobLevel())
                    : levelFitWeight(level, m.mobLevel()) * distanceWeight(level, m.hops(), reach);
            double w = (n >= cap) ? 0.0 : base * ((cap - n) / (double) cap); // hard cap + headroom weight
            weights[i] = w;
            total += w;
        }
        if (total <= 0) {
            // Every reachable map is at capacity — re-weight with the soft divisor so the surplus still
            // spreads onto the least-crowded map instead of idling in town.
            for (int i = 0; i < maps.size(); i++) {
                TrainingMap m = maps.get(i);
                double base = roamer
                        ? roamerFitWeight(m.mobLevel())
                        : levelFitWeight(level, m.mobLevel()) * distanceWeight(level, m.hops(), reach);
                double w = base / (1.0 + botsOnMap(scope, m.mapId()));
                weights[i] = w;
                total += w;
            }
        }
        if (total <= 0) {
            return maps.get(rng.nextInt(maps.size()));
        }
        double r = rng.nextDouble() * total;
        for (int i = 0; i < maps.size(); i++) {
            r -= weights[i];
            if (r <= 0) {
                return maps.get(i);
            }
        }
        return maps.get(maps.size() - 1);
    }

    // Full weight within (or above) the comfort band; below it, the weight decays with the gap so
    // far-below maps fade out smoothly instead of holding a flat "chill" weight.
    private static double levelFitWeight(int level, int mobLevel) {
        int comfortLow = minMobFor(level); // full weight across the whole admitted band (noobs love low maps)
        if (mobLevel >= comfortLow) {
            return 1.0;
        }
        int gap = comfortLow - mobLevel; // only bites the fallback edge (below the admitted floor)
        return Math.exp(-gap / (double) LEVEL_FIT_DECAY);
    }

    // Roamer fit: prefer the LOWEST mob level, decaying softly so the roamer spreads across the low
    // range instead of every bot converging on the single easiest map. A mob at level 1 is full weight;
    // level 60 (the ceiling) is e^-3 ≈ 0.05, so it is still possible but rare — a wanderer, not a swarm.
    private static double roamerFitWeight(int mobLevel) {
        int gap = Math.max(0, mobLevel - ROAMER_MOB_FLOOR);
        return Math.exp(-gap / (double) ROAMER_MOB_DECAY);
    }

    // The lowest mob level a bot will normally grind: floors at 1 for noobs (level <= LOWER_SPAN + 1) so
    // the maps right outside town stay populated, and sits LOWER_SPAN below level for higher bots.
    private static int minMobFor(int level) {
        return Math.max(1, level - LOWER_SPAN);
    }

    // Distance preference, ramped by level. reach = the bot's hop radius; hops = this map's BFS distance
    // from the spawn town (1 = adjacent). A fresh bot favors close maps; the preference slides toward the
    // far edge of its reach as it approaches VENTURE_FULL_LEVEL. With only adjacent maps reachable
    // there's nothing to bias, so weight is flat.
    private static double distanceWeight(int level, int hops, int reach) {
        if (reach <= 1) {
            return 1.0;
        }
        double venture = Math.min(1.0, level / (double) VENTURE_FULL_LEVEL);
        double hopFrac = (hops - 1) / (double) (reach - 1); // 0 = nearest, 1 = edge of reach
        hopFrac = Math.max(0.0, Math.min(1.0, hopFrac));
        double pref = (1.0 - venture) * (1.0 - hopFrac) + venture * hopFrac;
        return DISTANCE_WEIGHT_BASE + pref;
    }
}
