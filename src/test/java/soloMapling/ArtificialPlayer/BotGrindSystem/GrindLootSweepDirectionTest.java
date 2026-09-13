package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the "sideways shuffle" while tidying a drop pile: the lull sweep re-derived its walk
 * direction from the current nearest drop EVERY tick. A fresh drop cannot be grabbed for ~1-1.5s
 * (LOOT_SETTLE_MS), so the bot walks PAST it; once past, the nearest drop flips to the other side and
 * the walk reverses — a left-right stutter that overwrote the walk moves even though both piles sat
 * well within reach. Before the fix a two-sided pile shook ~8-11 times in 60s; now the sweep commits a
 * direction and turns at most once (to cover the other side).
 *
 * <p>Drives {@link GrindLoot#sweepStep} (the production direction picker) inside a faithful tick loop
 * — the settle gate, the 60px pickup, the sweep pacing, ~125px/s walk — mirroring
 * {@code GrindLoot.tryWalkAndLoot}.
 */
class GrindLootSweepDirectionTest {

    private static final int PICKUP = 60;
    private static final long TICK_MS = 250;
    private static final double SPEED_PER_TICK = 125.0 * TICK_MS / 1000.0;
    private static final int ARRIVE = 2;
    private static final int AT_FEET = 24;
    private static final int FAR_PX = 120;
    private static final long AT_FEET_SETTLE_MS = 1_000;
    private static final long SETTLE_MS = 1_500;

    private record Result(int reversals, int left) {
    }

    private static long settleMs(double dist) {
        if (dist <= AT_FEET) {
            return AT_FEET_SETTLE_MS;
        }
        if (dist >= FAR_PX) {
            return SETTLE_MS;
        }
        double t = (dist - AT_FEET) / (double) (FAR_PX - AT_FEET);
        return Math.round(AT_FEET_SETTLE_MS + t * (SETTLE_MS - AT_FEET_SETTLE_MS));
    }

    /** Drops carry their spawn time so the settle gate applies; all start on the ground at t=0. */
    private static Result sweep(List<Double> startDrops, double botX, int ticks) {
        List<double[]> drops = new ArrayList<>(); // {x, dropTimeMs}
        for (double x : startDrops) {
            drops.add(new double[] {x, 0});
        }
        boolean haveDir = false;
        boolean right = false;
        int lastDir = 0;
        int reversals = 0;
        for (int t = 0; t < ticks && !drops.isEmpty(); t++) {
            long now = (long) t * TICK_MS;
            int nearIdx = 0;
            double nearD = Double.MAX_VALUE;
            for (int i = 0; i < drops.size(); i++) {
                double d = Math.abs(drops.get(i)[0] - botX);
                if (d < nearD) {
                    nearD = d;
                    nearIdx = i;
                }
            }
            int[] xs = new int[drops.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = (int) Math.round(drops.get(i)[0]);
            }
            int[] step = GrindLoot.sweepStep(xs, (int) Math.round(botX), nearIdx, haveDir, right);
            right = step[0] != 0;
            haveDir = true;
            int target = xs[step[1]];
            int dir = Integer.compare(target, (int) Math.round(botX));
            if (dir != 0 && lastDir != 0 && dir != lastDir) {
                reversals++;
            }
            if (dir != 0) {
                lastDir = dir;
            }
            // Walk toward the far end.
            double dist = target - botX;
            if (Math.abs(dist) > ARRIVE) {
                botX += Math.min(SPEED_PER_TICK, Math.abs(dist)) * Math.signum(dist);
            }
            // Vacuum whatever has settled at the bot's feet this tick.
            for (int i = drops.size() - 1; i >= 0; i--) {
                double d = Math.abs(drops.get(i)[0] - botX);
                if (d <= PICKUP && now - drops.get(i)[1] >= settleMs(d)) {
                    drops.remove(i);
                }
            }
        }
        return new Result(reversals, drops.size());
    }

    private static List<Double> spread(int span, int step) {
        List<Double> out = new ArrayList<>();
        for (int x = -span; x <= span; x += step) {
            out.add((double) x);
        }
        return out;
    }

    @Test
    void twoSidedPileIsClearedWithoutSwaying() {
        // Pre-fix these swayed 5-6 times; the committed sweep turns at most once to cover the far side.
        for (int span : new int[] {60, 100, 150, 200}) {
            int step = Math.max(8, span / 10);
            Result r = sweep(spread(span, step), 0, 240);
            assertEquals(0, r.left(), "pile +-" + span + " not fully cleared");
            assertTrue(r.reversals() <= 2,
                    "pile +-" + span + " swayed: " + r.reversals() + " reversals (expected <= 2)");
        }
    }

    @Test
    void singleSidedChainWalksOneWay() {
        List<Double> oneSided = new ArrayList<>();
        for (int x = 20; x <= 160; x += 20) {
            oneSided.add((double) x); // all drops on one side of the bot
        }
        // Pre-fix this stuttered 4 times (outran the fresh drops' settle, then flipped on the nearest);
        // now at most one restitution turn — the bot overshoots drops it cannot grab yet and comes back.
        Result r = sweep(oneSided, 0, 240);
        assertEquals(0, r.left(), "one-sided pile not fully cleared");
        assertTrue(r.reversals() <= 1, "one-sided chain stuttered: " + r.reversals() + " reversals");
    }

    @Test
    void directionCommitsToTheNearSideFirst() {
        int[] xs = {-200, -100, 100, 200};
        int botX = 30;
        int[] first = GrindLoot.sweepStep(xs, botX, 2 /* near = 100 */, false, false);
        assertEquals(1, first[0], "should initially sweep toward the near (right) side");
        assertEquals(3, first[1], "far end is the right-most drop");
    }

    @Test
    void directionTurnsOnlyWhenThisSideIsEmpty() {
        int[] xs = {-200, -100, 100, 200};
        int botX = 0;
        // Right-committed, right side still populated -> stays right.
        int[] keep = GrindLoot.sweepStep(xs, botX, 2, true, true);
        assertEquals(1, keep[0]);
        assertEquals(3, keep[1]);
        // Right-committed but only left drops left -> flips once to the left.
        int[] flip = GrindLoot.sweepStep(new int[] {-200, -100}, botX, 1, true, true);
        assertEquals(0, flip[0]);
        assertEquals(0, flip[1], "far end is the left-most drop");
    }
}
