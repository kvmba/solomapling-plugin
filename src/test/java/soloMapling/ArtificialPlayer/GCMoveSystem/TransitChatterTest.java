package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Transit chatter: the idle lines bots drop while waiting or riding. They are scene-specific on
 * purpose — a bot in the elevator box must not talk about watching the sea, a subway passenger must
 * not complain about the boat — so this pins two things that a silent or mismatched edit would break:
 *
 * <ul>
 *   <li>every vehicle map resolves to a chatter set (a missing mapping leaves the ride mute);</li>
 *   <li>every set has exactly {@link #LINES_PER_SET} lines, contiguous and resolving in BOTH
 *       languages (a gap would surface as a raw key, "transit.onboard_boat.42", in front of a
 *       player once the line-count probe has fixed the range).</li>
 * </ul>
 *
 * The map→set table lives in GCTransit (package-private), so this sits in its package; BotMessages
 * needs no server.
 */
class TransitChatterTest {

    private static final int LINES_PER_SET = 100;

    // Every set GCTravel can ask for: the two wait sets, the seven ride sets, the attack shouts.
    private static final List<String> SETS = List.of(
            "elevator_wait", "ride_wait",
            "onboard_elevator", "onboard_boat", "onboard_train", "onboard_airship",
            "onboard_genie", "onboard_subway", "onboard_plane",
            "attack_shout"
    );

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void everyVehicleMapResolvesToAChatterSet() {
        // The onboard maps a bot can be carried in (see GCTransit.VEHICLE_MAPS for the full list).
        int[] onboard = {
                200090000, 200090001, 200090010, 200090011, // boat decks/cabins
                200090100, 200090110,                       // train decks
                200090200, 200090210,                       // airship decks/cabins
                200090400, 200090410,                       // genie carpet
                600010003, 600010005,                       // subway cars
                540010101, 540010002,                       // plane cabins
                222020110, 222020111, 222020210, 222020211  // elevator cars
        };
        for (int mapId : onboard) {
            assertNotNull(GCTransit.onboardChatterSet(mapId),
                    "vehicle map " + mapId + " has no chatter set — its passengers would ride mute");
        }
        // A non-vehicle map has no set (the caller then stays silent by design).
        assertEquals(null, GCTransit.onboardChatterSet(100000000));
    }

    @Test
    void eachRideGetsItsOwnSceneSet() {
        // Distinct vehicles must not share a set, or a boat line could play in the subway.
        assertEquals("onboard_elevator", GCTransit.onboardChatterSet(222020110));
        assertEquals("onboard_boat", GCTransit.onboardChatterSet(200090000));
        assertEquals("onboard_train", GCTransit.onboardChatterSet(200090100));
        assertEquals("onboard_airship", GCTransit.onboardChatterSet(200090200));
        assertEquals("onboard_genie", GCTransit.onboardChatterSet(200090400));
        assertEquals("onboard_subway", GCTransit.onboardChatterSet(600010005));
        assertEquals("onboard_plane", GCTransit.onboardChatterSet(540010002));
    }

    @Test
    void everySetHasExactlyOneHundredLinesInBothLanguages() {
        for (String tag : new String[]{"en-US", "zh-CN"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            for (String set : SETS) {
                for (int i = 0; i < LINES_PER_SET; i++) {
                    String key = "transit." + set + "." + i;
                    assertFalse(BotMessages.get(key).equals(key),
                            tag + " is missing " + key + " (would surface as a raw key in chat)");
                }
                // One past the end must NOT resolve — otherwise the probe would keep reading.
                String over = "transit." + set + "." + LINES_PER_SET;
                assertEquals(over, BotMessages.get(over),
                        tag + " has more than " + LINES_PER_SET + " lines in " + set);
            }
        }
    }

    @Test
    void theElevatorAndSubwayDoNotShareLines() {
        // Sanity that the scene split is real, not a copy: the two sets must be disjoint.
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        Set<String> elevator = Set.of(
                BotMessages.get("transit.onboard_elevator.0"),
                BotMessages.get("transit.onboard_elevator.1"));
        for (String line : elevator) {
            assertFalse(line.length() == 0, "elevator line should not be blank");
        }
        assertFalse(linesOverlap("onboard_elevator", "onboard_subway"),
                "elevator and subway scene lines must not be identical");
    }

    @Test
    void theLineProbeCapCoversEveryLine() throws Exception {
        // chatterLineCount probes transit.<set>.0.. up to GCTravel.CHATTER_MAX_LINES. If that cap is
        // ever below a set's size, the tail of the set becomes unreachable at runtime (the lines
        // exist, the test above passes, but a passenger can never say them). Pin it.
        java.lang.reflect.Field f = GCTravel.class.getDeclaredField("CHATTER_MAX_LINES");
        f.setAccessible(true);
        int cap = (int) f.get(null);
        assertFalse(cap < LINES_PER_SET,
                "CHATTER_MAX_LINES (" + cap + ") must be >= the set size (" + LINES_PER_SET
                        + ") or the last lines are unreachable");
    }

    @Test
    void theNamesNoVehicleInTheWaitLine() {
        // ride_wait is the GENERIC waiting set: one pool serves waiting at every boat / train / plane
        // / subway / genie counter, and those counters live in ordinary towns too (e.g. Kerning City's
        // air ticket counter). A line naming a specific vehicle ("is the boat here yet", "train or
        // bus") therefore reads wrong for every other ride and, worse, off a town street — the
        // "bot on a town street asking if the boat is here" report. The scene-specific pool belongs in
        // the per-vehicle onboard_* sets, not here. Pin ride_wait to transport-agnostic wording.
        // ASCII nouns are matched on word boundaries so a harmless word can't trip it ("training" vs
        // "train", "island" vs "land"); CJK has no word boundaries, so those are plain substrings.
        java.util.regex.Pattern ascii = java.util.regex.Pattern.compile(
                "\\b(boat|ship|ferry|sail|deck|cabin|dock|harbor|pier|"
                        + "train|railway|carriage|bus|subway|metro|platform|"
                        + "plane|flight|airplane|aircraft|pilot|captain|crew|runway|takeoff|land)s?\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        String[] cjk = {"船", "舟", "艇", "渡", "港", "码头", "候船", "甲板", "船舱", "靠岸", "登船", "舷",
                "火车", "列车", "车厢", "铁轨", "大巴", "公交", "地铁", "站台", "飞机", "航班", "登机",
                "电梯", "船票", "船长", "机组"};
        for (String tag : new String[]{"en-US", "zh-CN"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            for (int i = 0; i < LINES_PER_SET; i++) {
                String line = BotMessages.get("transit.ride_wait." + i);
                boolean asciiHit = ascii.matcher(line).find();
                assertFalse(asciiHit,
                        tag + " transit.ride_wait." + i + " names a vehicle: " + line);
                for (String noun : cjk) {
                    assertFalse(line.contains(noun),
                            tag + " transit.ride_wait." + i + " names a vehicle (" + noun + "): " + line);
                }
            }
        }
    }

    private static boolean linesOverlap(String a, String b) {
        for (int i = 0; i < LINES_PER_SET; i++) {
            for (int j = 0; j < LINES_PER_SET; j++) {
                if (BotMessages.get("transit." + a + "." + i)
                        .equals(BotMessages.get("transit." + b + "." + j))) {
                    return true;
                }
            }
        }
        return false;
    }
}
