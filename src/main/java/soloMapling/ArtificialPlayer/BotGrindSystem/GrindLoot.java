package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.client.Character;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

// Organic loot collection shared by every grind style: the passive at-feet vacuum that never
// interrupts a swing, and the between-kills walk-and-loot sweep. The caller passes its own leash
// and search range, so camp sweeps its spot, roam sweeps a box around the bot, and later styles
// bring their own bounds. Extracted verbatim from the pre-split GrindBrain. Ours (SoloMapling).
final class GrindLoot {

    private static final int LOOT_PICKUP_PX = 60;            // close enough to grab
    private static final long LOOT_GAP_MIN_MS = 100;         // stagger between at-feet pickups
    private static final long LOOT_GAP_MAX_MS = 350;
    private static final long LOOT_SWEEP_GAP_MIN_MS = 80;    // tighter pacing while drive-by sweeping (walk-over at ~125px/s clears ~60px spacing)
    private static final long LOOT_SWEEP_GAP_MAX_MS = 200;
    private static final int SWEEP_PICKUP_CAP = 2;           // pickups per combat tick while sweeping (keeps the anim readable)
    static final int LOOT_SAME_LEDGE_Y = 120;                // sweep: only walk to drops near the bot's Y (keep loot on its ledge)
    // Let a fresh drop finish its fly-out arc and settle on the floor before the bot grabs it — instant
    // pickup looks like the item teleports into the bot mid-air. Aged against MapItem.getDropTime() (the
    // server clock stamp set when the drop spawns), so it's "this drop has been on the ground ≥ this long".
    private static final long LOOT_SETTLE_MS = 1_500;
    private static final long LOOT_NARRATE_GAP_MS = 6_000;   // throttle loot lines (pickups are frequent)

    private final GrindBrain b;
    private long lootNextMs = 0L;
    private long lastLootNarrateMs = 0L;

    GrindLoot(GrindBrain brain) {
        this.b = brain;
    }

    void reset() {
        lootNextMs = 0L;
        lastLootNarrateMs = 0L;
    }

    // Passive at-feet loot: scoop a single drop sitting directly at the bot's feet (staggered), wherever the
    // bot is standing or walking. Runs every combat tick; never moves the bot or interrupts the swing, so the
    // bot only collects loot it is literally on top of (LOOT_PICKUP_PX). Distant drops are left for a
    // reposition to pass over, or the lull sweep (tryWalkAndLoot).
    void grabLootAtFeet(Character chr) {
        if (now() < lootNextMs) {
            return;
        }
        MapItem drop = nearestCollectableDrop(chr, LOOT_PICKUP_PX, Integer.MIN_VALUE, Integer.MAX_VALUE, LOOT_PICKUP_PX);
        if (drop != null) {
            DropCommands.botLootSingleDrop(chr, drop);
            lootNextMs = now() + lootGapMs();
            b.markProgress(); // collecting loot is productive, not wedged
            narrateLoot("scooping up loot at my feet");
        }
    }

    // Between kills, sweep the whole drop pile in one continuous motion instead of the old stop-and-go: gather
    // every eligible drop in [x0, x1] (the caller's leash), enter the chain at the near end and walk through to
    // the far end, vacuuming whatever the bot passes over — no hard stop, no re-approach per item. Advanced
    // classes blink/dash to the near end first. Returns true when there is loot to deal with this tick (so the
    // caller skips chasing a mob / holds its lull); false = nothing collectable, do your dry behaviour.
    boolean tryWalkAndLoot(Character chr, int x0, int x1, int searchRangePx) {
        List<MapItem> drops = collectableDrops(chr, searchRangePx, x0, x1, LOOT_SAME_LEDGE_Y, false);
        if (drops.isEmpty()) {
            return false;
        }
        Point pos = chr.getPosition();
        if (pos == null) {
            return true; // loot exists but we can't route this tick — still "there's loot to deal with"
        }
        // Route: near end = closest drop; far end = the farthest drop on the near end's side. Walking from the
        // bot through the near end to the far end passes over the whole chain in one direction; drops on the far
        // side of the bot are caught on a later sweep once the near ones are gone.
        MapItem near = drops.get(0);
        double nearSq = pos.distanceSq(near.getPosition());
        for (MapItem mi : drops) {
            double dsq = pos.distanceSq(mi.getPosition());
            if (dsq < nearSq) {
                nearSq = dsq;
                near = mi;
            }
        }
        boolean sweepRight = near.getPosition().x >= pos.x;
        MapItem far = near;
        for (MapItem mi : drops) {
            int mx = mi.getPosition().x;
            if (sweepRight ? mx > far.getPosition().x : mx < far.getPosition().x) {
                far = mi;
            }
        }
        // Class-skill approach: mages blink / hermits flash-jump to the near end of the chain, then the sweep
        // walk covers the rest. skillMoveToward carries every safety gate (style, cooldown, min distance,
        // onDifferentLedge refusal); WALK-style bots and any refusal fall through to the plain walk below.
        Point nearGp = GCMovement.groundPointBelow(chr.getMap(), near.getPosition().x, near.getPosition().y);
        int nearY = (nearGp != null) ? nearGp.y : near.getPosition().y;
        if (b.engage.skillMoveToward(chr, near.getPosition().x, nearY)) {
            b.engaged = false;
            narrateLoot("blinking over to a loot chain");
            return true; // blinked this tick; the sweep walk issues next tick once grounded
        }
        // Drive-by walk to the far end — no GCMovement.stop. Re-issue only when the far target shifts past the
        // epsilon (existing retarget pattern) so the walk runs uninterrupted across ticks.
        b.engaged = false;
        walkToFarthestOnSide(chr, drops, near, far, x0, x1, "sweeping through a chain of drops");
        vacuumWhileSweeping(chr);
        return true;
    }

    /**
     * Post-kill collection: walk onto the drop the mob just left and wait out its settle there.
     *
     * <p>A kill clears the target, so the next tick would normally acquire a fresh mob and walk to it —
     * leaving the drop behind, since it is not collectable for LOOT_SETTLE_MS and the bot is out of
     * at-feet range long before that. The pile then only gets picked up during a lull, and the lull sweep
     * keeps losing its move to the next spawn, which reads as the bot shuffling sideways instead of
     * looting. Doing it right after the kill is both what a player does and what makes the drop reachable.
     *
     * <p>Walks to drops that have not settled yet (the settle gate only gates the pickup, not the walk),
     * so the bot is standing on the loot by the time it can be grabbed. Returns true while there is loot
     * to collect, so the caller skips chasing a mob this tick.
     */
    boolean collectAfterKill(Character chr, int x0, int x1, int searchRangePx) {
        List<MapItem> drops = collectableDrops(chr, searchRangePx, x0, x1, LOOT_SAME_LEDGE_Y, true);
        if (drops.isEmpty()) {
            return false;
        }
        Point pos = chr.getPosition();
        if (pos == null) {
            return false;
        }
        // The at-feet vacuum already ran this tick (each strategy calls it before engaging), and it is
        // paced, so a second call here would be a no-op — just walk onto the drop and let that tick's
        // (or the next one's) vacuum take it once it has settled.
        MapItem near = drops.get(0);
        double nearSq = pos.distanceSq(near.getPosition());
        for (MapItem mi : drops) {
            double dsq = pos.distanceSq(mi.getPosition());
            if (dsq < nearSq) {
                nearSq = dsq;
                near = mi;
            }
        }
        // Close enough already: hold position and let the settle finish (grabLootAtFeet picks it up).
        if (Math.abs(near.getPosition().x - pos.x) <= LOOT_PICKUP_PX
                && Math.abs(near.getPosition().y - pos.y) <= LOOT_PICKUP_PX) {
            b.engaged = false;
            return true;
        }
        Point nearGp = GCMovement.groundPointBelow(chr.getMap(), near.getPosition().x, near.getPosition().y);
        int nearY = (nearGp != null) ? nearGp.y : near.getPosition().y;
        if (b.engage.skillMoveToward(chr, near.getPosition().x, nearY)) {
            b.engaged = false;
            return true;
        }
        b.engaged = false;
        // Walk onto the NEAREST drop, not the far end of a chain: this is one kill's loot, not a lull
        // sweep, so the bot should grab it and get back to fighting rather than tour the pile.
        int tx = GrindBrain.clamp(near.getPosition().x, x0, x1);
        if (Math.abs(tx - b.lastMoveTargetX) >= GrindBrain.ROAM_RETARGET_EPS) {
            Point gp = GCMovement.groundPointBelow(chr.getMap(), near.getPosition().x, near.getPosition().y);
            int ty = (gp != null) ? gp.y : near.getPosition().y;
            GCMovement.move(chr, tx, ty);
            b.lastMoveTargetX = tx;
            narrateLoot("collecting the drop I just made");
        }
        return true;
    }

    /** Walk to the chain's far end on the near end's side (see tryWalkAndLoot for the routing). */
    private void walkToFarthestOnSide(Character chr, List<MapItem> drops, MapItem near, MapItem seed,
                                      int x0, int x1, String narration) {
        Point pos = chr.getPosition();
        boolean sweepRight = near.getPosition().x >= pos.x;
        MapItem far = seed;
        for (MapItem mi : drops) {
            int mx = mi.getPosition().x;
            if (sweepRight ? mx > far.getPosition().x : mx < far.getPosition().x) {
                far = mi;
            }
        }
        int tx = GrindBrain.clamp(far.getPosition().x, x0, x1);
        if (Math.abs(tx - b.lastMoveTargetX) >= GrindBrain.ROAM_RETARGET_EPS) {
            Point gp = GCMovement.groundPointBelow(chr.getMap(), far.getPosition().x, far.getPosition().y);
            int ty = (gp != null) ? gp.y : far.getPosition().y;
            GCMovement.move(chr, tx, ty);
            b.lastMoveTargetX = tx;
            narrateLoot(narration);
        }
    }

    private static final int AT_FEET_PX = 24;        // "right underfoot"
    private static final int FAR_PX = 120;           // at or beyond this, the full wait applies
    private static final long AT_FEET_SETTLE_MS = 250;
    /** Squared bounds, so the hot path can stay on distSq without a sqrt. */
    private static final double AT_FEET_SQ = (double) AT_FEET_PX * AT_FEET_PX;
    private static final double FAR_SQ = (double) FAR_PX * FAR_PX;

    /**
     * How long a drop must sit before the bot may grab it, by how far away it is.
     *
     * <p>The settle gate exists so a drop is not yanked out of the air the instant it spawns -
     * that reads as the item teleporting into the bot. But the wait only has to cover the drop's
     * flight, and a drop landing AT THE BOT'S FEET is what a kill actually produces: standing on
     * it for a second and a half before bending down looks wrong, and it is also what let kills
     * look like they dropped nothing (the bot walks to the next mob before the loot is legal).
     *
     * <p>So: right underfoot is nearly instant, further away waits the full arc - a distant drop
     * is one the bot walks to, and by the time it arrives the wait has passed anyway. Scales
     * linearly between the two so there is no visible step.
     */
    private static long settleMsFor(double distSq) {
        if (distSq <= AT_FEET_SQ) {
            return AT_FEET_SETTLE_MS;
        }
        if (distSq >= FAR_SQ) {
            return LOOT_SETTLE_MS;
        }
        double t = (Math.sqrt(distSq) - AT_FEET_PX) / (double) (FAR_PX - AT_FEET_PX);
        return Math.round(AT_FEET_SETTLE_MS + t * (LOOT_SETTLE_MS - AT_FEET_SETTLE_MS));
    }

    private MapItem nearestCollectableDrop(Character chr, int rangePx, int x0, int x1, int yLimit) {
        Point pos = chr.getPosition();
        if (pos == null) {
            return null;
        }
        double searchSq = (double) rangePx * rangePx;
        MapItem best = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : chr.getMap().getMapObjectsInRange(pos, searchSq, List.of(MapObjectType.ITEM))) {
            MapItem mi = (MapItem) mo;
            if (!DropCommands.botCanLoot(chr, mi)) {
                continue;
            }
            Point ip = mi.getPosition();
            if (ip.x < x0 || ip.x > x1 || Math.abs(ip.y - pos.y) > yLimit) {
                continue; // outside the leash, or on a stacked ledge above/below (unreachable)
            }
            double dsq = pos.distanceSq(ip);
            if (now() - mi.getDropTime() < settleMsFor(dsq)) {
                continue; // still settling for this distance — it'll be collected on a later tick
            }
            if (dsq < bestSq) {
                bestSq = dsq;
                best = mi;
            }
        }
        return best;
    }

    // Every eligible drop in [x0, x1] within rangePx and the same-ledge Y band — the multi-drop counterpart to
    // nearestCollectableDrop (identical botCanLoot / leash / ledge filters), used to plan a whole sweep.
    // `includeUnsettled` skips the settle check for callers that only want to WALK to a drop: a kill leaves
    // its loot on the ground for LOOT_SETTLE_MS before it may be picked up, and a bot that walks off to the
    // next mob in the meantime is outside the at-feet range by the time it could have been grabbed.
    private List<MapItem> collectableDrops(Character chr, int rangePx, int x0, int x1, int yLimit,
                                           boolean includeUnsettled) {
        Point pos = chr.getPosition();
        if (pos == null) {
            return List.of();
        }
        double searchSq = (double) rangePx * rangePx;
        List<MapItem> out = new ArrayList<>();
        for (MapObject mo : chr.getMap().getMapObjectsInRange(pos, searchSq, List.of(MapObjectType.ITEM))) {
            MapItem mi = (MapItem) mo;
            if (!DropCommands.botCanLoot(chr, mi)) {
                continue;
            }
            if (!includeUnsettled && now() - mi.getDropTime() < LOOT_SETTLE_MS) {
                continue; // too fresh — let it land first; the pass-over grabs whatever has settled by then
            }
            Point ip = mi.getPosition();
            if (ip.x < x0 || ip.x > x1 || Math.abs(ip.y - pos.y) > yLimit) {
                continue; // outside the leash, or on a stacked ledge above/below (unreachable)
            }
            out.add(mi);
        }
        return out;
    }

    // In-sweep vacuum: while the sweep walk carries the bot across the chain, grab up to SWEEP_PICKUP_CAP drops
    // sitting at its feet this tick — WITHOUT halting or cancelling the walk (the GCMovement.stop was what made
    // the old loot stop-and-go). Paced by the tightened sweep gap so a walk-over doesn't outrun the pacer, and
    // settle is still enforced (via nearestCollectableDrop) so mid-flight drops aren't yanked in.
    private void vacuumWhileSweeping(Character chr) {
        if (now() < lootNextMs) {
            return;
        }
        int picked = 0;
        while (picked < SWEEP_PICKUP_CAP) {
            MapItem drop = nearestCollectableDrop(chr, LOOT_PICKUP_PX, Integer.MIN_VALUE, Integer.MAX_VALUE, LOOT_PICKUP_PX);
            if (drop == null) {
                break;
            }
            DropCommands.botLootSingleDrop(chr, drop);
            picked++;
            b.markProgress(); // collecting loot is productive, not wedged
        }
        if (picked > 0) {
            lootNextMs = now() + sweepGapMs();
            narrateLoot("scooping loot on the move");
        }
    }

    private long sweepGapMs() {
        return LOOT_SWEEP_GAP_MIN_MS + (long) (b.rng.nextDouble() * (LOOT_SWEEP_GAP_MAX_MS - LOOT_SWEEP_GAP_MIN_MS));
    }

    private long lootGapMs() {
        return LOOT_GAP_MIN_MS + (long) (b.rng.nextDouble() * (LOOT_GAP_MAX_MS - LOOT_GAP_MIN_MS));
    }

    // Throttled loot narration — pickups are frequent, so cap one line per LOOT_NARRATE_GAP_MS. Its
    // ABSENCE is itself a signal: a stationed ranged/magic bot that never closes on a kill never loots.
    private void narrateLoot(String msg) {
        if (!GrindBrain.NARRATE) {
            return;
        }
        if (now() - lastLootNarrateMs >= LOOT_NARRATE_GAP_MS) {
            lastLootNarrateMs = now();
            b.debugLine(msg);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
