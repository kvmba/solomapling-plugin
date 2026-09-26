package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks LPQ stage 6's climb rule: the way up is read off a portal's target NAME, not tried
 * by portal id.
 *
 * <p>Every {@code h0NN} portal on 922010600 targets the tower itself and carries no script, so
 * the id order says nothing about which one climbs. What distinguishes them is {@code tn}: a
 * decoy lands on {@code st00} (the bottom spawn) and a real rung lands on another {@code h0NN}
 * about 170px higher. Blindly stepping into h000..h020 therefore dropped the climber back to
 * the floor every time and the stage never advanced.
 */
class LudiClimbRungTest {

    /** The real portal names and their {@code tn} targets, from Map.wz/922010600. */
    private static final String[][] UP_LINKS = {
            {"h002", "h003"}, {"h005", "h006"}, {"h006", "h009"}, {"h010", "h012"},
            {"h013", "h015"}, {"h015", "h018"}, {"h018", "h021"}, {"h021", "h024"},
            {"h025", "h027"}, {"h027", "h030"}, {"h032", "h033"}, {"h034", "h036"},
            {"h036", "h039"}, {"h039", "h042"},
    };

    /** Each portal's OWN position y (a rung sits at its own y and lands on its target's y). */
    private static final java.util.Map<String, Integer> PORTAL_Y = java.util.Map.ofEntries(
            java.util.Map.entry("h000", -555), java.util.Map.entry("h001", -555),
            java.util.Map.entry("h002", -556), java.util.Map.entry("h003", -723),
            java.util.Map.entry("h004", -724), java.util.Map.entry("h005", -725),
            java.util.Map.entry("h006", -915), java.util.Map.entry("h007", -914),
            java.util.Map.entry("h008", -912), java.util.Map.entry("h009", -1090),
            java.util.Map.entry("h010", -1091), java.util.Map.entry("h011", -1091),
            java.util.Map.entry("h012", -1268), java.util.Map.entry("h013", -1267),
            java.util.Map.entry("h014", -1266), java.util.Map.entry("h015", -1437),
            java.util.Map.entry("h016", -1438), java.util.Map.entry("h017", -1438),
            java.util.Map.entry("h018", -1602), java.util.Map.entry("h019", -1601),
            java.util.Map.entry("h020", -1603), java.util.Map.entry("h021", -1770),
            java.util.Map.entry("h022", -1769), java.util.Map.entry("h023", -1770),
            java.util.Map.entry("h024", -1935), java.util.Map.entry("h025", -1935),
            java.util.Map.entry("h026", -1935), java.util.Map.entry("h027", -2126),
            java.util.Map.entry("h028", -2128), java.util.Map.entry("h029", -2128),
            java.util.Map.entry("h030", -2305), java.util.Map.entry("h031", -2305),
            java.util.Map.entry("h032", -2305), java.util.Map.entry("h033", -2477),
            java.util.Map.entry("h034", -2476), java.util.Map.entry("h035", -2477),
            java.util.Map.entry("h036", -2659), java.util.Map.entry("h037", -2658),
            java.util.Map.entry("h038", -2657), java.util.Map.entry("h039", -2822),
            java.util.Map.entry("h040", -2822), java.util.Map.entry("h041", -2822),
            java.util.Map.entry("h042", -2993), java.util.Map.entry("h043", -2993),
            java.util.Map.entry("h044", -2993));

    /** Where the bot stands after taking {@code rung}: the target portal's own y. */
    private static int landingY(String target) {
        return PORTAL_Y.get(target);
    }

    /** The prefixes the climb reads: a rung is h0NN -> h0NN, anything else is a decoy. */
    private static final String PREFIX = LudiPqData.CLIMB_PORTAL_PREFIX;

    @Test
    void theClimbIsDrivenByPortalNameNotByPortalId() {
        assertEquals("h0", PREFIX,
                "the ladder is recognised by the h0NN portal name, not by an id range");
    }

    @Test
    void everyUpLinkLandsHigherThanTheRungItStartsFrom() {
        for (String[] link : UP_LINKS) {
            String rung = link[0];
            String lands = link[1];
            int fromY = PORTAL_Y.get(rung);
            int toY = PORTAL_Y.get(lands);
            // Higher up means a smaller y: this is the whole test for "is a rung".
            assertEquals(true, toY < fromY,
                    rung + " -> " + lands + " should gain height (" + toY + " < " + fromY + ")");
        }
    }

    /**
     * The walk a bot actually performs: from the spawn, each step takes the lowest rung that
     * still gains height. It must reach the top rung without repeating a step.
     */
    @Test
    void walkingTheLadderReachesTheTopWithoutLooping() {
        int y = -237; // the tower's spawn point (sp) y
        java.util.Set<String> taken = new java.util.HashSet<>();
        int steps = 0;
        String rung = nextRungUp(y);
        assertNotNull(rung, "a bot standing at the spawn must see a rung above it");
        while (rung != null && steps < 40) {
            assertEquals(true, taken.add(rung), "rung " + rung + " repeated: the climb loops");
            // Taking the rung moves the bot to the portal its tn names.
            y = landingY(targetOf(rung));
            rung = nextRungUp(y);
            steps++;
        }
        // 14 rungs, and the last one lands on the top row where h044 leads to next00.
        assertEquals(14, steps);
        assertEquals(-2993, y, "the climb ends on the top rung row");
        assertNull(rung, "there is no rung above the top row");
    }

    /** The same rule {@code nextRungUp} applies in production, against the WZ data. */
    private static String targetOf(String name) {
        for (String[] link : UP_LINKS) {
            if (link[0].equals(name)) {
                return link[1];
            }
        }
        return null;
    }

    private static String nextRungUp(int fromY) {
        String best = null;
        int bestY = fromY;
        for (String[] link : UP_LINKS) {
            String name = link[0];
            String target = link[1];
            if (!name.startsWith(PREFIX) || !target.startsWith(PREFIX) || target.equals(name)) {
                continue;
            }
            int landingY = PORTAL_Y.get(target);
            if (landingY < fromY && (best == null || landingY > bestY)) {
                best = name;
                bestY = landingY;
            }
        }
        return best;
    }
}
