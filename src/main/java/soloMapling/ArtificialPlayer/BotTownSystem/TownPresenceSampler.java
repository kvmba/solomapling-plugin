package soloMapling.ArtificialPlayer.BotTownSystem;

import org.gms.server.maps.Foothold;
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

// Centre-hot town scatter: pick N ground spots across a map's reachable walkable ground, densest at the
// map's horizontal centre and thinning toward the left/right ends. Zero map-specific code - every signal
// is WZ-derived:
//
//   - A ledge's draw weight is its WALKABLE WIDTH times its CENTRALITY (see centerHotspotWeight): a pixel
//     near the map's horizontal centre is ~4.4x as likely as one at the extreme end. So the crowd reads as
//     a town square - busiest mid-map, a few stragglers out at the edges - rather than an even sprinkle or
//     an NPC/portal hotspot.
//   - Capacity and spacing keep it from being crowded: a ledge absorbs at most one bot per MIN_BAND_PX of
//     width (overflow spills by width onto other platforms), and picks on one ledge keep MIN_SPACING apart.
//   - Curation overrides compose: a ledge whose centre sits in a ban zone is dropped (weight 0) and a
//     boost zone multiplies a ledge's weight (pull more of the crowd to a plaza).
//
// Reads terrain only through the generic GCMovement spatial queries (same discipline as BotSpotPicker),
// so placement stays out of the movement package. Our own creation (not a GreenCat extraction).
public final class TownPresenceSampler {

    private TownPresenceSampler() {
    }

    private static final Random RANDOM = new Random();

    // Best-effort horizontal gap between spots sharing a ledge, and how hard we try to honor it.
    private static final int MIN_SPACING = 30;
    private static final int X_CANDIDATES = 7; // X samples scored per ledge pick (least-crowded wins)
    // Rejection tries to land a centre-biased X before falling back to a plain uniform draw.
    private static final int X_CENTRAL_TRIES = 5;

    // Min walkable width one hosted bot needs (room to stand apart and drift a little). Caps how many
    // picks a single ledge may absorb: without this a wide street could draw the WHOLE cohort and stack
    // it end to end. Mirrors the section-per-band capacity BotSpotClaims enforces on the same ledges once
    // the bots are live.
    private static final int MIN_BAND_PX = 200;

    // How close (in Y) a ledge must sit to the lowest ledge to count as the map's floor band. The
    // floor of a town is rarely one platform: it is the sheet of street and terrace at the bottom, and
    // its pieces sit up to a step apart (Kerning's widest floor piece is 116px and its neighbours are
    // ~30px higher). 40 takes those in while still excluding a genuinely separate first floor.
    private static final int FLOOR_BAND_TOLERANCE = 40;

    // Centre-hot falloff: a ledge's weight is scaled by how near its X sits to the map's horizontal
    // centre, as a gaussian with sigma = this fraction of the half-span. 0.58 puts the extreme end at
    // exp(-1 / (2 * 0.58^2)) = ~0.226, i.e. the middle of the map is ~4.4x as busy as its ends - a town
    // square that thickens toward the centre without emptying the edges.
    private static final double CENTER_SIGMA_FRAC = 0.58;

    // Centrality weight in X alone: 1.0 at the map's horizontal centre, ~0.22 at either extreme end.
    // Only X is used - a town reads as a left-right street band, so the crowd thickens mid-map while
    // upper/lower ledges keep whatever the width weighting already gives them. Returns 1.0 when the span
    // is a point (nothing to bias). Package-private so a test can pin the curve without a MapleMap.
    static double centerHotspotWeight(int x, int minX, int maxX) {
        double half = (maxX - minX) / 2.0;
        if (half <= 0.0) {
            return 1.0;
        }
        double d = (x - (minX + maxX) / 2.0) / (CENTER_SIGMA_FRAC * half);
        return Math.exp(-0.5 * d * d);
    }

    // Pick up to `count` ground spots spread across `map`, reachable from `anchor` (the town spawn
    // portal). Returns fewer than count only if the map has no ground at all; an unbaked nav graph is
    // covered by a random foothold scatter, so the caller never has to stack bots on one anchor pixel.
    public static List<Point> sample(MapleMap map, Point anchor, int count) {
        return sample(map, anchor, count, TownOverrides.EMPTY);
    }

    // As sample(), but composing the curation overrides: pinned spots are placed first, ban zones are
    // never placed in, and boost zones pull more of the crowd.
    public static List<Point> sample(MapleMap map, Point anchor, int count, TownOverrides overrides) {
        return sample(map, anchor, count, overrides, false);
    }

    // As sample(), but with `floorOnly` the crowd is confined to the map's lowest ground band. Used by
    // GachaBot: it sprays a pile of items at its feet, and an item dropped on an upper platform lands on
    // the floor below (calcDropPos drops from y-85 to the first foothold under it), where the bot can't
    // reach it - so it would pile up unclaimed. Standing on the floor keeps the whole spray retrievable.
    public static List<Point> sample(MapleMap map, Point anchor, int count, TownOverrides overrides,
                                     boolean floorOnly) {
        return sample(map, anchor, count, overrides, floorOnly, false);
    }

    // As above, but ledges whose live town claim is already at capacity are dropped from the draw when
    // `avoidCrowdedLedges` is set. This is what stops a pause point from landing on a platform a crowd
    // already fills: the stationed SocialBots claim their ledges through BotSpotClaims, and a later
    // relocate/scatter (which samples a SINGLE point, so the sampler's own in-call spacing can do nothing)
    // now sees those claims and steers elsewhere. Nothing is dropped when every eligible ledge is full, so
    // a caller still gets a spot rather than nothing on a map the population has outgrown.
    public static List<Point> sample(MapleMap map, Point anchor, int count, TownOverrides overrides,
                                     boolean floorOnly, boolean avoidCrowdedLedges) {
        List<Point> out = new ArrayList<>();
        if (map == null || anchor == null || count <= 0) {
            return out;
        }
        TownOverrides ov = overrides != null ? overrides : TownOverrides.EMPTY;

        // Pinned spots first (deliberate hand-polish) - snap each down to the foothold under it.
        for (Point pin : ov.pins()) {
            if (out.size() >= count) {
                return out;
            }
            Point ground = GCMovement.groundPointBelow(map, pin.x, pin.y);
            out.add(ground != null ? ground : new Point(pin));
        }
        int remaining = count - out.size();
        if (remaining <= 0) {
            return out;
        }

        List<GCMovement.Ledge> ledges = reachableLedges(map, anchor);
        if (ledges.isEmpty()) {
            // The nav graph yields no walkable ledge (unbaked / degenerate map). Rather than return an empty
            // list - which makes the caller stack every bot on one anchor pixel - top up with a terrain
            // scatter across the map's raw footholds, so the cohort still lands spread out.
            out.addAll(footholdScatter(map, count - out.size(), floorOnly));
            return out;
        }
        if (floorOnly) {
            ledges = floorBand(ledges);
        }
        if (avoidCrowdedLedges) {
            // Drop ledges whose live town claim is already full - only the ones this draw could use, so the
            // read stays proportional to the eligible set (and respects the floorBand filter above).
            int mapId = map.getId();
            List<GCMovement.Ledge> open = new ArrayList<>();
            for (GCMovement.Ledge l : ledges) {
                if (!BotSpotClaims.isFull(mapId, l.regionId())) {
                    open.add(l);
                }
            }
            if (!open.isEmpty()) {
                ledges = open;
            }
        }

        double[] weights = new double[ledges.size()];
        int spanMinX = Integer.MAX_VALUE;
        int spanMaxX = Integer.MIN_VALUE;
        for (GCMovement.Ledge l : ledges) {
            spanMinX = Math.min(spanMinX, l.minX());
            spanMaxX = Math.max(spanMaxX, l.maxX());
        }
        for (int i = 0; i < ledges.size(); i++) {
            GCMovement.Ledge l = ledges.get(i);
            weights[i] = ledgeWeight(l, ov) * centerHotspotWeight(l.centerX(), spanMinX, spanMaxX);
        }

        Map<Integer, List<Integer>> occupiedByLedge = new HashMap<>();
        for (int i = 0; i < remaining; i++) {
            GCMovement.Ledge l = pickLedge(ledges, weights, occupiedByLedge);
            List<Integer> taken = occupiedByLedge.computeIfAbsent(l.regionId(), k -> new ArrayList<>());
            int x = pickX(l, taken, ov, spanMinX, spanMaxX);
            taken.add(x);
            Point ground = GCMovement.groundPointInRegion(map, l.regionId(), x);
            out.add(ground != null ? ground : new Point(x, l.centerY()));
        }
        return out;
    }

    // Terrain-only scatter for maps with no baked nav graph: `count` ground points at random footholds /
    // random X (via the per-column indexed ground query the movement engine uses, so no graph build is
    // triggered). `floorOnly` restricts the draw to the map's lowest foothold band, honoring the same
    // contract as the nav-ledge path (GachaBot's item spray must land on ground the bot can walk to).
    // Skips walls and zero-width footholds. Best-effort: fewer than `count` only when the map has almost
    // no ground.
    static List<Point> footholdScatter(MapleMap map, int count, boolean floorOnly) {
        List<Point> out = new ArrayList<>();
        if (map == null || map.getFootholds() == null || count <= 0) {
            return out;
        }
        List<Foothold> walkable = new ArrayList<>();
        for (Foothold f : map.getFootholds().getAllFootholds()) {
            if (!f.isWall() && f.getX2() > f.getX1()) {
                walkable.add(f);
            }
        }
        if (floorOnly) {
            walkable = footholdFloorBand(walkable);
        }
        if (walkable.isEmpty()) {
            return out;
        }
        for (int i = 0; i < count; i++) {
            Foothold f = walkable.get(RANDOM.nextInt(walkable.size()));
            int x = f.getX1() + RANDOM.nextInt(f.getX2() - f.getX1() + 1);
            // Ground line at x (slope-aware). groundPointBelow probes from the foothold's y, so it always
            // finds this foothold rather than one below it.
            Point ground = GCMovement.groundPointBelow(map, x, Math.min(f.getY1(), f.getY2()));
            out.add(ground != null ? ground : new Point(x, Math.max(f.getY1(), f.getY2())));
        }
        return out;
    }

    // Lowest foothold band: footholds whose mid Y sits within FLOOR_BAND_TOLERANCE of the map's deepest
    // foothold (largest Y, since Y grows downward). The terrain-only twin of floorBand for the nav ledges.
    // Package-private so a test can pin the band contract without a MapleMap.
    static List<Foothold> footholdFloorBand(List<Foothold> walkable) {
        int floor = Integer.MIN_VALUE;
        for (Foothold f : walkable) {
            floor = Math.max(floor, (f.getY1() + f.getY2()) / 2);
        }
        List<Foothold> out = new ArrayList<>();
        for (Foothold f : walkable) {
            if (floor - (f.getY1() + f.getY2()) / 2 <= FLOOR_BAND_TOLERANCE) {
                out.add(f);
            }
        }
        return out;
    }

    // Reachable walkable ledges. Returns empty when the nav graph has no walkable ledge; sample() then
    // uses footholdScatter so a spawn never collapses onto one point.
    //
    // Swim maps skip the reachability filter: their ledges (a deck, a floating platform) are separate
    // islands in the walk graph - the only way between them is swimming, which the graph does not model -
    // so filtering would confine every pick to the arrival platform and pile the crowd on its door. The
    // runtime swim integrator follows a raw target across open water (see GCMovement), so a spot on any
    // ledge is reachable; the whole map is the fair draw.
    private static List<GCMovement.Ledge> reachableLedges(MapleMap map, Point anchor) {
        List<GCMovement.Ledge> all = GCMovement.walkableLedges(map);
        if (all.isEmpty() || map.isSwim()) {
            return all;
        }
        Set<Integer> reachable = GCMovement.reachableRegions(map, anchor.x, anchor.y);
        if (reachable.isEmpty()) {
            return all;
        }
        List<GCMovement.Ledge> out = new ArrayList<>();
        for (GCMovement.Ledge l : all) {
            if (reachable.contains(l.regionId())) {
                out.add(l);
            }
        }
        return out.isEmpty() ? all : out;
    }

    // Just the ledges in the map's lowest band: anything whose centreY sits within FLOOR_BAND_TOLERANCE
    // of the lowest ledge on the map (Y grows downward, so the largest Y is the floor). Used to keep a
    // crowd off the platforms above the floor.
    private static List<GCMovement.Ledge> floorBand(List<GCMovement.Ledge> ledges) {
        int floor = Integer.MIN_VALUE;
        for (GCMovement.Ledge l : ledges) {
            floor = Math.max(floor, l.centerY());
        }
        List<GCMovement.Ledge> out = new ArrayList<>();
        for (GCMovement.Ledge l : ledges) {
            if (floor - l.centerY() <= FLOOR_BAND_TOLERANCE) {
                out.add(l);
            }
        }
        return out;
    }

    // Per-ledge weight = walkable width, times the curation boost (no NPC/shop/doorway pull - that anchor
    // weighting was what packed the crowd onto one street). The centre-hot centrality is applied by the
    // caller on top of this. Curation overrides compose: a ledge whose centre sits in a ban zone is excluded
    // (weight 0). Package-private so a test can pin the width-proportional contract.
    static double ledgeWeight(GCMovement.Ledge l, TownOverrides ov) {
        if (ov.isBanned(l.centerX(), l.centerY())) {
            return 0.0;
        }
        double span = Math.max(1, l.maxX() - l.minX());
        return span * ov.boostMultiplier(l.centerX(), l.centerY());
    }

    // Pick an X on the ledge, honoring best-effort spacing, the centre-hot bias, and the curation overrides
    // (never land in a ban zone; a boost zone raises the local score). Candidates are drawn centre-biased
    // (centralX) so a single long street still thickens toward the middle; as before, the best-scoring one
    // wins (boost minus crowding), defaulting to the ledge's left end when every candidate is banned.
    private static int pickX(GCMovement.Ledge l, List<Integer> taken, TownOverrides ov,
                             int mapMinX, int mapMaxX) {
        if (l.maxX() <= l.minX()) {
            return l.minX();
        }
        int best = l.minX();
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < X_CANDIDATES; i++) {
            int x = centralX(l, mapMinX, mapMaxX);
            if (ov.isBanned(x, l.centerY())) {
                continue; // never place inside a ban zone
            }
            double score = ov.boostMultiplier(x, l.centerY()) - crowding(x, taken);
            if (score > bestScore) {
                bestScore = score;
                best = x;
            }
        }
        return best;
    }

    // A centre-biased X in the ledge's span: rejection-sampled with density proportional to the centrality
    // weight, so picks concentrate toward the map's horizontal middle. Falls back to a plain uniform draw
    // after a few tries (a candidate far from centre is simply more likely to be rejected and retried).
    private static int centralX(GCMovement.Ledge l, int mapMinX, int mapMaxX) {
        int lo = l.minX();
        int hi = l.maxX();
        if (hi <= lo) {
            return lo;
        }
        for (int i = 0; i < X_CENTRAL_TRIES; i++) {
            int x = lo + RANDOM.nextInt(hi - lo + 1);
            if (RANDOM.nextDouble() < centerHotspotWeight(x, mapMinX, mapMaxX)) {
                return x;
            }
        }
        return lo + RANDOM.nextInt(hi - lo + 1);
    }

    // Penalty for landing within MIN_SPACING of an already-taken X on this ledge (keeps spots from stacking).
    private static double crowding(int x, List<Integer> taken) {
        double penalty = 0.0;
        for (int t : taken) {
            int d = Math.abs(t - x);
            if (d < MIN_SPACING) {
                penalty += (MIN_SPACING - d); // closer -> heavier penalty
            }
        }
        return penalty;
    }

    // Pick which ledge the next spot lands on: a width-weighted draw that skips ledges already at their
    // width-derived capacity, so the cohort spills sideways onto other platforms instead of stacking on one
    // street. If every ledge is full the cohort has genuinely outgrown the map; the overflow then spreads by
    // platform width (same distribution, ignoring the capacity filter).
    static GCMovement.Ledge pickLedge(List<GCMovement.Ledge> ledges, double[] weights,
                                      Map<Integer, List<Integer>> occupiedByLedge) {
        double total = 0.0;
        for (int i = 0; i < ledges.size(); i++) {
            if (hasRoom(ledges.get(i), occupiedByLedge)) {
                total += weights[i];
            }
        }
        if (total <= 0.0) {
            return pickByWidth(ledges); // every ledge full -> spread the overflow by platform size
        }
        double r = RANDOM.nextDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < ledges.size(); i++) {
            if (hasRoom(ledges.get(i), occupiedByLedge)) {
                cumulative += weights[i];
                if (r < cumulative) {
                    return ledges.get(i);
                }
            }
        }
        return ledges.get(ledges.size() - 1);
    }

    private static boolean hasRoom(GCMovement.Ledge l, Map<Integer, List<Integer>> occupiedByLedge) {
        return occupiedByLedge.getOrDefault(l.regionId(), List.of()).size() < ledgeCapacity(l);
    }

    // Overflow sharing (and the fallback when every ledge is full): weight each ledge by its walkable width
    // so the surplus spreads in proportion to available ground, never re-concentrating on one platform.
    private static GCMovement.Ledge pickByWidth(List<GCMovement.Ledge> ledges) {
        long total = 0;
        for (GCMovement.Ledge l : ledges) {
            total += Math.max(1, l.maxX() - l.minX());
        }
        long r = (long) (RANDOM.nextDouble() * total);
        long cumulative = 0;
        for (GCMovement.Ledge l : ledges) {
            cumulative += Math.max(1, l.maxX() - l.minX());
            if (r < cumulative) {
                return l;
            }
        }
        return ledges.get(ledges.size() - 1);
    }

    // How many hosted bots one ledge may absorb: one per MIN_BAND_PX of walkable width, at least one so a
    // narrow ledge still hosts a bot. A long street holds a few; a short platform stays single-occupancy.
    static int ledgeCapacity(GCMovement.Ledge l) {
        return Math.max(1, (l.maxX() - l.minX()) / MIN_BAND_PX);
    }

    // Human-readable dump of where the scatter concentrates: ledge count + the top-N ledges by weight
    // (region id, X span, centerY, relative weight %). With the centre-hot weight this is essentially the
    // wide platforms near the middle of the map. Feeds the !env townpresence weights command so tuning is
    // tune -> look -> tune without a restart. Diagnostics only.
    public static String describe(MapleMap map, Point anchor, int topN) {
        return describe(map, anchor, topN, TownOverrides.EMPTY);
    }

    public static String describe(MapleMap map, Point anchor, int topN, TownOverrides overrides) {
        if (map == null || anchor == null) {
            return "town weights: no map/anchor";
        }
        TownOverrides ov = overrides != null ? overrides : TownOverrides.EMPTY;
        List<GCMovement.Ledge> ledges = reachableLedges(map, anchor);
        if (ledges.isEmpty()) {
            return "town weights: no baked ledges (nav graph not built yet)";
        }
        double total = 0.0;
        List<double[]> rows = new ArrayList<>(); // {regionId, minX, maxX, centerY, weight}
        int spanMinX = Integer.MAX_VALUE;
        int spanMaxX = Integer.MIN_VALUE;
        for (GCMovement.Ledge l : ledges) {
            spanMinX = Math.min(spanMinX, l.minX());
            spanMaxX = Math.max(spanMaxX, l.maxX());
        }
        for (GCMovement.Ledge l : ledges) {
            double w = ledgeWeight(l, ov) * centerHotspotWeight(l.centerX(), spanMinX, spanMaxX);
            total += w;
            rows.add(new double[]{l.regionId(), l.minX(), l.maxX(), l.centerY(), w});
        }
        rows.sort((a, b) -> Double.compare(b[4], a[4]));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "town weights map=%d: %d ledges, %d pins (centre-hot by walkable width+centrality)%n",
                map.getId(), ledges.size(), ov.pins().size()));
        int limit = Math.min(topN, rows.size());
        for (int i = 0; i < limit; i++) {
            double[] r = rows.get(i);
            double pct = total > 0 ? 100.0 * r[4] / total : 0.0;
            sb.append(String.format("  #%d region=%d x=[%.0f..%.0f] y=%.0f  %.1f%%%n",
                    i + 1, (int) r[0], r[1], r[2], r[3], pct));
        }
        return sb.toString();
    }
}
