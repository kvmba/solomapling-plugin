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
            {100000000, 110000000, 1},  // Victoria Island + Sleepywood (Henesys/Ellinia/Perion/Kerning/Lith + fields/dungeons)
            {200000000, 201000000, 30}, // Orbis (town, tower, cloud park, sky fields)
            {211000000, 212000000, 30}, // El Nath (town + dungeon: Ice Valley, Wolf Territory, Sharp Cliff, Dead Mine)
            {220000000, 223000000, 30}, // Ludibrium continent: Ludibrium + Eos Tower + Deep Ludi, plus Omega Sector / Korean Folk Town (original v83)
            {250000000, 252000000, 30}, // Mu Lung + Herb Town — reachable via Hak (2090005) from Orbis Sky
            {700000000, 783000000, 1},  // Shanghai: Bund, Plaza, and the mainland maps behind them (up to 782000002)
            {230000000, 231000000, 30}, // Aquarium — plain portals in from Orbis Tower / the storybook well
            {240000000, 241000000, 70}, // Leafre — reachable via the Cabin from Orbis
            {260000000, 262000000, 30}, // Ariant — reachable via the Genie from Orbis
            {300000000, 301000000, 30}, // Arta camp (阿尔泰营地) — behind the Helios elevator's time gate
                                        // Not Rien (里恩, 140000000): that one stays out, see docs.
            {600000000, 601000000, 1},  // New Leaf City — reachable by subway from Kerning
            {540000000, 542000000, 1},  // Singapore (CBD) — reachable by plane from Kerning
            {270000000, 271000000, 90}, // Time Temple — only as a dragon, via the Halfling in Leafre
    };

    // Level a bot must have reached before it may treat mapId as a destination.
    public static boolean isAllowed(int mapId, int level) {
        int[] region = regionOf(mapId);
        return region != null && level >= region[2];
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
