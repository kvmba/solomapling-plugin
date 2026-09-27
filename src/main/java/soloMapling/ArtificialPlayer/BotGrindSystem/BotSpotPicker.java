package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSpotClaims;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

// Picks an organic ground spot on a map, WZ-driven. This is the generalization of the old two-point
// filler scatter (spawnFillerBots): instead of one flat line at a single Y, it gathers EVERY walkable
// ledge whose X-extent overlaps the requested [x1,x2] band - including platforms stacked vertically at
// different Y within that band - then picks one weighted by how much of it lies in the band and returns
// a slope-aware ground point on it.
//
// Reads terrain only through the generic GCMovement spatial queries (same discipline as GrindSpotFinder),
// so placement logic stays out of the movement package. Our own creation (not a GreenCat extraction).
public final class BotSpotPicker {

    private BotSpotPicker() {
    }

    private static final Random RANDOM = new Random();

    // Horizontal gap we try to keep between batch-placed bots sharing a ledge (best-effort).
    private static final int MIN_SPACING = 30;

    // How many spaced-X attempts before we accept an overlap on a crowded ledge.
    private static final int SPACING_ATTEMPTS = 8;

    // Pick one organic ground point anywhere on the map's reachable terrain. fromX/fromY anchor the
    // reachability filter (use the spawn portal) so we never land on a disconnected island ledge.
    // Returns null when the nav graph isn't baked / there's no eligible ledge - caller should fall back.
    public static Point pickGroundSpot(MapleMap map, int fromX, int fromY) {
        return pickGroundSpot(map, fromX, fromY, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    // Pick one organic ground point on a ledge whose X overlaps [x1,x2]. Vertically stacked platforms in
    // the band are all eligible (no Y constraint). Returns null when nothing qualifies - caller falls back.
    public static Point pickGroundSpot(MapleMap map, int fromX, int fromY, int x1, int x2) {
        return pickGroundSpot(map, fromX, fromY, x1, x2, false);
    }

    // As above; `avoidCrowdedLedges` drops the candidates whose live town claim (BotSpotClaims) is already
    // at capacity, so a caller that strolls through town - the flavor wander - never targets a ledge the
    // stationed crowd has filled. Only the candidates are tested (not every ledge on the map), keeping the
    // read proportional to what this pick can actually use. Nothing is dropped when every candidate is full
    // or nothing is claimed yet, so a roam still moves on an over-packed map.
    public static Point pickGroundSpot(MapleMap map, int fromX, int fromY, int x1, int x2,
                                       boolean avoidCrowdedLedges) {
        List<Candidate> candidates = eligibleLedges(map, fromX, fromY, x1, x2);
        if (avoidCrowdedLedges && !candidates.isEmpty()) {
            int mapId = map.getId();
            List<Candidate> open = new ArrayList<>();
            for (Candidate c : candidates) {
                if (!BotSpotClaims.isFull(mapId, c.ledge.regionId())) {
                    open.add(c);
                }
            }
            if (!open.isEmpty()) {
                candidates = open;
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Candidate c = pickWeightedByWidth(candidates);
        int x = randomInSpan(c.lo, c.hi);
        return groundAt(map, c, x);
    }

    // Pick one organic ground point within maxDistPx of (fromX, fromY) in X, preferring one at least
    // minDistPx away, that the bot can actually WALK to. An unbounded whole-map pick lands either on
    // the pixel the bot already occupies (a twitch, not a step) or clear across the map (a trek that
    // keeps the bot in transit for most of the wait it is meant to be filling). Bounded both ways the
    // pick is what "stroll" means: a short walk to another part of the same room.
    //
    // The walkability test is the part that matters most. A region is the walk-connected union of a
    // floor band, but reachability across the whole GRAPH (see eligibleLedges -> reachableRegions) says
    // nothing about the legwork between two floors: a spot 300px away in X but one storey up needs a
    // rope/jump the stroll never commits to, and a move() aimed there never arrives - the bot hangs on
    // its moveTarget forever (moveTarget only clears on arrival/abandon), holds isMoving() true, and
    // every later stroll no-ops. So each candidate is validated against the bot's own floor: prefer the
    // ledge the caller is standing on, fall back to a candidate the nav graph can genuinely path to,
    // and - rather than issue a move it cannot complete - give up when neither holds. null means "stay
    // put", which is always safer than a target the bot will hang on.
    public static Point pickGroundSpotNear(MapleMap map, int fromX, int fromY, int minDistPx, int maxDistPx,
                                           java.util.function.BiPredicate<Integer, Point> canWalkTo) {
        int homeRegion = GCMovement.regionIdAt(map, fromX, fromY);
        List<Candidate> candidates = eligibleLedges(map, fromX, fromY, fromX - maxDistPx, fromX + maxDistPx);
        if (candidates.isEmpty()) {
            return null;
        }
        // The ledge the bot stands on needs no graph query: any x on it is a plain walk. Sample it
        // first so a wide floor (the usual lobby case) never pays for a cross-candidate validation.
        Candidate home = null;
        for (Candidate c : candidates) {
            if (c.ledge.regionId() == homeRegion) {
                home = c;
                break;
            }
        }
        if (home != null) {
            for (int i = 0; i < SPACING_ATTEMPTS; i++) {
                int x = randomInSpan(home.lo, home.hi);
                if (Math.abs(x - fromX) >= minDistPx) {
                    return groundAt(map, home, x);
                }
            }
        }
        // Nothing on the home ledge clears minDistPx (a narrow floor). Other in-band ledges are only
        // honest options when the graph can path to them; each accepted spot is validated once, so the
        // retry loop costs at most SPACING_ATTEMPTS graph walks.
        Candidate best = null;
        int bestX = fromX;
        int bestDist = -1;
        for (int i = 0; i < SPACING_ATTEMPTS; i++) {
            Candidate c = pickWeightedByWidth(candidates);
            int x = randomInSpan(c.lo, c.hi);
            int dist = Math.abs(x - fromX);
            if (dist <= bestDist) {
                continue;
            }
            if (canWalkTo.test(c.ledge.regionId(), groundAt(map, c, x))) {
                best = c;
                bestX = x;
                bestDist = dist;
                if (dist >= minDistPx) {
                    return groundAt(map, c, x);
                }
            }
        }
        // A short step on the home ledge still beats a cross-floor hang: prefer it over the farthest
        // candidate, which may need a rope the stroll will not commit to. bestX can legitimately be
        // fromX (every sampled cross candidate was rejected), and a zero-distance move would be a
        // no-op twitch, so fall back to the home ledge's farthest end instead.
        if (home != null) {
            int x = bestX != fromX ? bestX : clampSpan(home, fromX);
            return groundAt(map, home, x);
        }
        return null;
    }

    // The farthest end of the ledge from fromX - the fallback for a floor too short to stroll across,
    // and for a home ledge that could not offer minDistPx on its own samples.
    private static int clampSpan(Candidate c, int fromX) {
        return Math.abs(c.hi - fromX) >= Math.abs(c.lo - fromX) ? c.hi : c.lo;
    }

    // Whole-map batch with light anti-cluster. Returns up to `count` spots; an empty list means the
    // caller should fall back (e.g. spawn everyone at the portal).
    public static List<Point> pickGroundSpots(MapleMap map, int fromX, int fromY, int count) {
        return pickGroundSpots(map, fromX, fromY, Integer.MIN_VALUE, Integer.MAX_VALUE, count);
    }

    // Band-constrained batch. Spreads `count` spots across the eligible ledges, weighted by their span in
    // the band, keeping a best-effort min spacing between spots on the same ledge.
    public static List<Point> pickGroundSpots(MapleMap map, int fromX, int fromY, int x1, int x2, int count) {
        List<Point> out = new ArrayList<>();
        List<Candidate> candidates = eligibleLedges(map, fromX, fromY, x1, x2);
        if (candidates.isEmpty() || count <= 0) {
            return out;
        }
        Map<Integer, List<Integer>> occupiedByLedge = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Candidate c = pickWeightedByWidth(candidates);
            List<Integer> taken = occupiedByLedge.computeIfAbsent(c.ledge.regionId(), k -> new ArrayList<>());
            int x = pickSpacedX(c.lo, c.hi, taken);
            taken.add(x);
            out.add(groundAt(map, c, x));
        }
        return out;
    }

    // Build the eligible ledge set: walkable ledges that overlap [x1,x2] in X and are reachable from
    // (fromX,fromY). If the anchor sits on no ledge, reachability can't be resolved so we don't filter on
    // it (matches GrindSpotFinder), keeping behavior graceful rather than empty.
    private static List<Candidate> eligibleLedges(MapleMap map, int fromX, int fromY, int x1, int x2) {
        List<Candidate> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        List<GCMovement.Ledge> ledges = GCMovement.walkableLedges(map);
        if (ledges.isEmpty()) {
            return out;
        }
        int bandLo = Math.min(x1, x2);
        int bandHi = Math.max(x1, x2);

        Set<Integer> reachable = GCMovement.reachableRegions(map, fromX, fromY);
        // Swim maps: their ledges are separate islands in the walk graph (the only link is swimming,
        // which the graph does not model), so a reachability filter would confine a roam to the platform
        // the bot arrived on. The swim integrator follows a raw target across open water, so every ledge
        // is reachable - don't filter.
        boolean filter = !reachable.isEmpty() && !map.isSwim();

        for (GCMovement.Ledge l : ledges) {
            if (filter && !reachable.contains(l.regionId())) {
                continue;
            }
            int lo = Math.max(l.minX(), bandLo);
            int hi = Math.min(l.maxX(), bandHi);
            if (hi < lo) {
                continue; // no X overlap with the band
            }
            out.add(new Candidate(l, lo, hi));
        }
        return out;
    }

    // Resolve the slope-aware ground point on a ledge at x, falling back to the ledge's center Y if the
    // region went away between query and resolve.
    private static Point groundAt(MapleMap map, Candidate c, int x) {
        Point ground = GCMovement.groundPointInRegion(map, c.ledge.regionId(), x);
        return ground != null ? ground : new Point(x, c.ledge.centerY());
    }

    // Weighted pick favoring ledges with more span inside the band (organic area-proportional spread).
    private static Candidate pickWeightedByWidth(List<Candidate> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        long total = 0;
        for (Candidate c : candidates) {
            total += c.weight();
        }
        long r = (long) (RANDOM.nextDouble() * total);
        long cumulative = 0;
        for (Candidate c : candidates) {
            cumulative += c.weight();
            if (r < cumulative) {
                return c;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static int randomInSpan(int lo, int hi) {
        return hi <= lo ? lo : lo + RANDOM.nextInt(hi - lo + 1);
    }

    // Random X in [lo,hi] that keeps MIN_SPACING from already-taken X's; accepts the last attempt if the
    // ledge is too crowded to honor spacing.
    private static int pickSpacedX(int lo, int hi, List<Integer> taken) {
        if (hi <= lo) {
            return lo;
        }
        int candidate = lo;
        for (int attempt = 0; attempt < SPACING_ATTEMPTS; attempt++) {
            candidate = lo + RANDOM.nextInt(hi - lo + 1);
            boolean clear = true;
            for (int t : taken) {
                if (Math.abs(t - candidate) < MIN_SPACING) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return candidate;
            }
        }
        return candidate;
    }

    // A walkable ledge clipped to the requested band: [lo,hi] is the usable X span on this ledge.
    private static final class Candidate {
        final GCMovement.Ledge ledge;
        final int lo;
        final int hi;

        Candidate(GCMovement.Ledge ledge, int lo, int hi) {
            this.ledge = ledge;
            this.lo = lo;
            this.hi = hi;
        }

        int weight() {
            return Math.max(1, hi - lo + 1);
        }
    }
}
