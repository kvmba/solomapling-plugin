package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential test: runs the band-building logic as it was BEFORE the bucketing
 * change and as it is NOW, over randomised inputs, and requires the two to agree.
 *
 * <p>{@link SpotFinderBucketedGateTest} proves the gate predicate itself is De Morgan
 * of the old one. This test covers what that test cannot: the control flow around
 * it -- the {@code preferred != null} branch, the break-on-first-match, the in-box
 * filter and the unbaked-map path -- by mirroring both implementations against a
 * shared fake region resolver. Both sides go through the same resolver, so any
 * divergence is in the logic under test, not in the stub.
 *
 * <p>It cannot call the real {@code bestStackHostile}: that needs {@code MapleMap} and
 * {@code Monster}, whose static init requires a running host. Mirroring the two
 * versions instead keeps the equivalence claim checkable in a plain unit test.
 */
class SpotFinderBandEquivalenceTest {

    private static final int UNBAKED = -2;

    private final Map<String, Integer> regionAt = new HashMap<>();
    private boolean baked = true;

    private int regionOf(Point p) {
        return regionAt.getOrDefault(p.x + "," + p.y, -1);
    }

    /** Stand-in for the pre-change GCMovement.onDifferentLedge. */
    private boolean legacyOnDifferentLedge(Point a, Point b) {
        if (!baked) {
            return false;
        }
        int ra = regionOf(a);
        int rb = regionOf(b);
        return ra >= 0 && rb >= 0 && ra != rb;
    }

    /** Stand-in for the new GCMovement.peekRegionIdAt. */
    private int peekRegion(Point p) {
        return baked ? regionOf(p) : UNBAKED;
    }

    private record Mob(int id, Point position) {
    }

    /** The band as it was built before the change. */
    private List<Integer> legacyBand(List<Mob> mobs, List<Point> members, Point preferred,
                                     int x0, int x1, int yTop, int yBottom) {
        List<Integer> band = new ArrayList<>();
        for (Mob m : mobs) {
            Point mp = m.position();
            if (mp == null || mp.x < x0 || mp.x > x1 || mp.y < yTop || mp.y > yBottom) {
                continue;
            }
            if (preferred != null) {
                if (!legacyOnDifferentLedge(preferred, mp)) {
                    band.add(m.id());
                }
                continue;
            }
            for (Point member : members) {
                if (!legacyOnDifferentLedge(member, mp)) {
                    band.add(m.id());
                    break;
                }
            }
        }
        return band;
    }

    /** The band as it is built now (mirrors SpotFinder.bestStackHostile). */
    private List<Integer> currentBand(List<Mob> mobs, List<Point> members, Point preferred,
                                      int x0, int x1, int yTop, int yBottom) {
        List<Point> anchors = (preferred != null) ? List.of(preferred) : members;
        int[] anchorRegions = new int[anchors.size()];
        boolean unbaked = false;
        for (int i = 0; i < anchors.size(); i++) {
            Point a = anchors.get(i);
            anchorRegions[i] = peekRegion(a);
            unbaked |= anchorRegions[i] == UNBAKED;
        }
        List<Integer> band = new ArrayList<>();
        for (Mob m : mobs) {
            Point mp = m.position();
            if (mp == null || mp.x < x0 || mp.x > x1 || mp.y < yTop || mp.y > yBottom) {
                continue;
            }
            if (unbaked) {
                band.add(m.id());
                continue;
            }
            int mobRegion = peekRegion(mp);
            if (mobRegion == UNBAKED) {
                band.add(m.id());
                continue;
            }
            for (int anchorRegion : anchorRegions) {
                if (SpotFinder.acceptsSameLedge(anchorRegion, mobRegion)) {
                    band.add(m.id());
                    break;
                }
            }
        }
        return band;
    }

    @Test
    void matchesTheLegacyBandOverRandomisedLayouts() {
        Random r = new Random(20260904L);
        int x0 = -100, x1 = 100, yTop = -100, yBottom = 100;

        for (int trial = 0; trial < 20_000; trial++) {
            regionAt.clear();

            List<Mob> mobs = new ArrayList<>();
            int mobCount = 1 + r.nextInt(12);
            for (int i = 0; i < mobCount; i++) {
                Point p;
                if (r.nextInt(10) == 0) {
                    p = null;                                       // dead/missing position
                } else if (r.nextInt(12) == 0) {
                    p = new Point(r.nextInt(400) - 200, r.nextInt(400) - 200); // out of box
                } else {
                    p = new Point(r.nextInt(x1 - x0) + x0, r.nextInt(yBottom - yTop) + yTop);
                }
                mobs.add(new Mob(i, p));
                if (p != null) {
                    regionAt.put(p.x + "," + p.y, r.nextInt(3) - 1);  // -1..2, -1 = on no ledge
                }
            }

            List<Point> members = new ArrayList<>();
            int memberCount = 1 + r.nextInt(5);
            for (int i = 0; i < memberCount; i++) {
                Point a = new Point(r.nextInt(40) - 20, r.nextInt(40) - 20);
                members.add(a);
                regionAt.put(a.x + "," + a.y, r.nextInt(3) - 1);
            }
            Point preferred = (r.nextInt(2) == 0) ? null
                    : new Point(r.nextInt(40) - 20, r.nextInt(40) - 20);
            if (preferred != null) {
                regionAt.put(preferred.x + "," + preferred.y, r.nextInt(3) - 1);
            }

            baked = (r.nextInt(10) != 0);   // mostly baked, sometimes not

            assertEquals(legacyBand(mobs, members, preferred, x0, x1, yTop, yBottom),
                    currentBand(mobs, members, preferred, x0, x1, yTop, yBottom),
                    "band diverged on trial " + trial + " (baked=" + baked + ")");
        }
    }

    @Test
    void bothVersionsAcceptEverythingWhenTheGraphIsUnbaked() {
        baked = false;
        List<Mob> mobs = List.of(new Mob(0, new Point(0, 0)), new Mob(1, new Point(50, 0)));
        List<Point> members = List.of(new Point(0, 0));
        assertEquals(List.of(0, 1), legacyBand(mobs, members, null, -100, 100, -100, 100));
        assertEquals(List.of(0, 1), currentBand(mobs, members, null, -100, 100, -100, 100));
    }

    @Test
    void bothVersionsFilterByLedgeWhenTheGraphIsBaked() {
        baked = true;
        regionAt.put("0,0", 0);
        regionAt.put("50,0", 1);
        List<Mob> mobs = List.of(new Mob(0, new Point(0, 0)), new Mob(1, new Point(50, 0)));
        List<Point> members = List.of(new Point(0, 0));   // ledge 0
        // Only the mob on ledge 0 is accepted; the one on ledge 1 is filtered out.
        assertEquals(List.of(0), legacyBand(mobs, members, null, -100, 100, -100, 100));
        assertEquals(List.of(0), currentBand(mobs, members, null, -100, 100, -100, 100));
    }
}
