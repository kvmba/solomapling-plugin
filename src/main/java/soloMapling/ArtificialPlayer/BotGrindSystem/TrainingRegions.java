package soloMapling.ArtificialPlayer.BotGrindSystem;

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
public final class TrainingRegions {

    private TrainingRegions() {}

    // Each row is {minInclusive, maxExclusive, minLevel}.
    private static final int[][] ALLOWED = {
            {0, 60002, 1},              // Beginner island: Mushroom Town, Snail Garden, Southperry and
                                        // their hunting grounds (map ids run 0..60001, below Victoria)
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
     * this a bot that outlevels its landmass grinds the same trivial mobs forever. This picks the
     * next continent whose mobs are still worth the bot's level — which is how a player leaves
     * Victoria for Orbis, and Orbis for Leafre.
     *
     * Ordered by the level a bot needs, so the answer is always "one step up", never a leap to the
     * deepest content. Returns 0 when the current continent still has mobs this bot can use.
     */
    /*
     * The rungs, in the order a bot outgrows them. Level is when this landmass stops being worth it:
     * the beginner island at 8 (also the level Sanks asks for), Victoria at 31 — second job is 30,
     * and that is when players take the boat out, well before its fields top out — and so on up.
     */
    private static final int BEGINNER_MIGRATE_LEVEL = 8;
    private static final int[][] MIGRATION_LADDER = {
            // Victoria at 31: second job is 30, and that is when players stop training on the island
            // and take the boat to Orbis — not when its fields finally top out.
            {100000000, 31},
            {200000000, 75},   // Orbis / El Nath
            {240000000, 100},  // Leafre
            {270000000, 999},  // Time Temple: the end of the ladder
    };

    /**
     * One rung back down, for a bot that feels like visiting: players drift back to the continents
     * they came from, and without it the low continents are emptied of everyone but beginners.
     * Returns 0 for the bottom rung, which has nowhere to go back to.
     */
    public static int returnTarget(int homeMapId) {
        for (int i = 0; i < MIGRATION_LADDER.length; i++) {
            if (MIGRATION_LADDER[i][0] == homeMapId) {
                return i > 0 ? MIGRATION_LADDER[i - 1][0] : 0;
            }
        }
        return 0; // not on the ladder
    }

    public static int migrationTarget(int homeMapId, int level) {
        // The beginner island is anywhere below Victoria's id range; a bot leaves it the moment it
        // can, which is also the level Sanks asks for.
        if (homeMapId < 100000) {
            return level >= BEGINNER_MIGRATE_LEVEL ? 100000000 : 0;
        }
        int current = -1;
        for (int i = 0; i < MIGRATION_LADDER.length; i++) {
            if (MIGRATION_LADDER[i][0] == homeMapId) {
                current = i;
                break;
            }
        }
        // Not on the ladder (a sub-town, or a continent with no further rung): nothing to migrate to.
        if (current < 0) {
            return 0;
        }
        if (level < MIGRATION_LADDER[current][1]) {
            return 0; // still gets along here
        }
        return current + 1 < MIGRATION_LADDER.length ? MIGRATION_LADDER[current + 1][0] : 0;
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
