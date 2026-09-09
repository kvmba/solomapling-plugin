package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.util.concurrent.ThreadLocalRandom;

// Which map regions training bots may grind in: original-MapleStory content only (Victoria Island +
// Sleepywood, Orbis, El Nath, Ludibrium continent). Keeps discovery off Aqua Road / Leafre / Mu Lung /
// Cygnus / new-school maps even when a portal makes them reachable. Checked in TrainingMapFinder before
// the level band. Windows are [min, max) over the 9-digit map-id region prefix (verified vs MapId.java:
// HENESYS 100000000, SLEEPYWOOD 105040300, ORBIS 200000000, EL_NATH 211000000, LUDIBRIUM 220000000;
// excluded anchors AQUARIUM 230000000, LEAFRE 240000000, ELLIN_FOREST 300000000). Ours (SoloMapling).
//
// Each window also carries the level a bot needs before that continent is a destination at all. A
// low-level bot never targets a far continent, so it stays where it is instead of walking to a
// terminal and standing there — the gate is applied when targets are chosen, not at the ticket
// counter, so nothing has to untangle a bot that already arrived somewhere it shouldn't be.
//
// The windows double as the migration map: window[0] is the continent's town (where a bot sets up
// home) and window[2] is the bar for treating it as a destination, so "where could this bot go next"
// is answered from the same table that decides where it may grind — no second list to drift.
public final class TrainingRegions {

    private TrainingRegions() {}

    // Each row is {minInclusive, maxExclusive, minLevel}.
    private static final int[][] ALLOWED = {
            // Beginner island: Mushroom Town, Snail Garden, Rainbow Village, Southperry
            // and their hunting grounds. The island's map ids are not a single
            // run: the walk from the spawn (10000) to Southperry (2000000) climbs
            // through 1000000/1010000/1020000, so a window that stopped at 60001
            // left the far half of the island — the half a companion must cross
            // to reach Sanks' boat — outside the maps a bot may train in.
            // 2010001 clears the island's last map (2000001, the armour store).
            {0, 2010001, 1},
            {110000000, 110040001, 30}, // Gold Beach (黄金海滩): reached by boat from Lith, Orbis or
                                        // Ludibrium. Gated at 30 — its lowest mob is 37.
            {120000000, 120010001, 1},  // Nautilus (诺特勒斯号码头): the pirate town, walked to from
                                        // Henesys' east woods. All town, no hunting grounds.
            {100000000, 110000000, 1},  // Victoria Island + Sleepywood (Henesys/Ellinia/Perion/Kerning/Lith + fields/dungeons)
            {200000000, 201000000, 30}, // Orbis (town, tower, cloud park, sky fields)
            {211000000, 212000000, 30}, // El Nath (town + dungeon: Ice Valley, Wolf Territory, Sharp Cliff, Dead Mine)
            {220000000, 223000000, 30}, // Ludibrium continent: Ludibrium + Eos Tower + Deep Ludi, plus Omega Sector / Korean Folk Town (original v83)
            {250000000, 251000000, 30}, // Mu Lung — reachable via Hak (2090005) from Orbis Sky
            {251000000, 252000000, 40}, // Herb Town (百草堂) — its own gate: lowest mob is 47
            {700000000, 783000000, 1},  // Shanghai: Bund, Plaza, and the mainland maps behind them (up to 782000002)
            {230000000, 231000000, 30}, // Aquarium — plain portals in from Orbis Tower / the storybook well
            {240000000, 241000000, 70}, // Leafre — reachable via the Cabin from Orbis
            {260000000, 262000000, 30}, // Ariant — reachable via the Genie from Orbis
            {300000000, 301000000, 30}, // Arta camp (阿尔泰营地) — behind the Helios elevator's time gate
                                        // Not Rien (里恩, 140000000): that one stays out, see docs.
            {600000000, 601000000, 1},  // New Leaf City — reachable by subway from Kerning
            {540000000, 542000000, 1},  // Singapore (CBD) — reachable by plane from Kerning
            {702000000, 703000000, 1},  // Songshan Town (嵩山镇, Mount Song / Shaolin): reached
                                        // through the travel agency's "东方神州" list.
            {800000000, 802000000, 30}, // Japan: Mushroom Shrine (古代神社) + Showa Village (昭和村).
                                        // Reached through the Maple Travel Agency (9000020), which
                                        // stands in every major town. No hunting grounds here — a
                                        // sightseeing continent, so it takes townsfolk, not grinders.
            {550000000, 552000000, 30}, // Malaysia: Trend Zone (吉隆大都市) + Kampung (甘榜村), via the
                                        // same agency from Boat Quay Town (541000000).
            {270000000, 271000000, 90}, // Time Temple — only as a dragon, via the Halfling in Leafre
    };

    // Level a bot must have reached before it may treat mapId as a destination.
    public static boolean isAllowed(int mapId, int level) {
        int[] region = regionOf(mapId);
        return region != null && level >= region[2];
    }

    /*
     * Where a bot should move its home once it has outgrown the continent it is standing on.
     *
     * A training bot only ever looks for grind maps within a few hops of where it stands, so without
     * this a bot that outlevels its landmass grinds the same trivial mobs forever. This picks a
     * continent whose mobs are still worth the bot's level — which is how a player leaves Victoria
     * for Orbis, and Orbis for Leafre.
     *
     * Seed the island: it is a one-way trip out, so it is never a destination a bot comes back to.
     */
    private static final int BEGINNER_MIGRATE_LEVEL = 8;
    /** Victoria Island's town — where the boat from the beginner island lands. */
    private static final int VICTORIA_ISLAND_START = 100_000_000;
    /**
     * From this level the ladder stops being a ladder: a bot picks ANY continent it qualifies for,
     * not just a harder one. 120 is fourth job — past it a player goes where they like, and a bot
     * that can only ever climb would end up leaving the low continents to beginners for good.
     */
    public static final int FREE_MOVE_LEVEL = 120;

    /**
     * A continent to move to: any other continent this bot qualifies for, picked at random.
     *
     * Random rather than "one rung up", because a bot's level says what it CAN handle, not which
     * single place it must be next — and a fixed ladder stranded every bot whose home was a town
     * that wasn't on it (a sub-town, or a whole continent with no rung at all). The gate is the
     * window's own minLevel, so a bot still never lands somewhere it is too low for.
     *
     * Returns 0 when nothing else is open to it — too low for anywhere new, or already everywhere.
     */
    public static int returnTarget(int homeMapId, int level) {
        return pickOther(homeMapId, level, 0);
    }

    /**
     * Where a bot that has outgrown its continent should head, or 0 to stay.
     *
     * Below {@link #FREE_MOVE_LEVEL} this only moves a bot UP: to a continent with a higher bar
     * than the one it stands on, so a bot climbs Victoria -> Orbis -> Leafre instead of wandering
     * between places it has already outlevelled. At {@link #FREE_MOVE_LEVEL} and up it may go
     * anywhere, including back down — a fourth-job bot has nothing left to climb, and the low
     * continents need somebody in them.
     *
     * The beginner island is every map below the Victoria range — and that range is not a small
     * one: the walk to Southperry runs through 1000000/1010000/1020000 up to 2000000, all still
     * below Victoria's 100000000. A bound of 100000 left a bot standing on Southperry's dock
     * thinking it had already left the island, so it never took the boat.
     */
    public static int migrationTarget(int homeMapId, int level) {
        if (homeMapId < VICTORIA_ISLAND_START) {
            return level >= BEGINNER_MIGRATE_LEVEL ? VICTORIA_ISLAND_START : 0;
        }
        int bar = barOf(homeMapId);
        // Off the map (a region-less id): nothing to compare against, so nothing to outgrow.
        if (bar < 0) {
            return 0;
        }
        return level >= FREE_MOVE_LEVEL
                ? pickOther(homeMapId, level, 0)                      // anywhere it qualifies for
                : pickOther(homeMapId, level, bar);                   // strictly a harder continent
    }

    /**
     * A random continent town other than the bot's own, whose bar this bot clears.
     *
     * @param above when non-zero, only continents with a bar strictly HIGHER than this qualify
     *              (the "climb" case); 0 accepts any bar (the "anywhere" case).
     */
    private static int pickOther(int homeMapId, int level, int above) {
        int home = anchorOf(homeMapId);
        int chosen = 0;
        int seen = 0;
        for (int[] window : ALLOWED) {
            int anchor = window[0];
            // The island is a way out, never a way back in, so it is not a destination.
            // Nor is an anchor an earlier window already swallows — its maps belong to that
            // continent (702000000 sits inside Shanghai's 700000000-783000000), so naming it
            // would send a bot "to another continent" that is the one it is standing in.
            if (anchor == 0 || anchor == home || anchorOf(anchor) != anchor) {
                continue;
            }
            if (level < window[2] || window[2] <= above) {
                continue;
            }
            // Reservoir pick: one pass, no list, uniform over everything that qualified.
            if (ThreadLocalRandom.current().nextInt(++seen) == 0) {
                chosen = anchor;
            }
        }
        return chosen;
    }

    /** The town a bot standing anywhere in this continent calls home. -1 when mapId is in no region. */
    private static int anchorOf(int mapId) {
        int[] region = regionOf(mapId);
        return region == null ? -1 : region[0];
    }

    /** The bar of the continent mapId is in, or -1 when it is in no region. */
    private static int barOf(int mapId) {
        int[] region = regionOf(mapId);
        return region == null ? -1 : region[2];
    }

    private static int[] regionOf(int mapId) {
        for (int[] window : ALLOWED) {
            if (mapId >= window[0] && mapId < window[1]) {
                return window;
            }
        }
        return null;
    }
}
