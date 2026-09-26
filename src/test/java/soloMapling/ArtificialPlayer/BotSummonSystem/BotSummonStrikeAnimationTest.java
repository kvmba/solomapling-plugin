package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.provider.DataProvider;
import org.gms.provider.wz.XMLWZFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the summon strike cadence: the cooldown is the skill's own {@code summon/attack1} animation
 * length (read from Skill.wz) plus the configured recovery pause - not one flat interval for every
 * summon.
 *
 * <p>The animation readings are checked against the host's real WZ tree through the host's own
 * parser, so the node path and the delay-summing rule are both exercised rather than assumed. The
 * test skips when the host checkout is not adjacent (the same guard the sibling parity test uses).</p>
 */
class BotSummonStrikeAnimationTest {

    /** The plugin's registered attacking summons, with the animation their WZ row carries. */
    private static final int[][] EXPECTED_MS = {
            {3121006, 930},   // Phoenix
            {3221005, 930},   // Frost Prey
            {5211001, 960},   // Octopus
            {5220002, 1080},  // Wrath of the Octopi
            {2321003, 1620},  // Bahamut
            {2121005, 2250},  // Elquines
            {2221005, 2280},  // Ifrit
            {3111005, 700},   // Silver Hawk
            {3211005, 700},   // Golden Eagle
            {2311006, 600},   // Summon Dragon
    };

    @Test
    void animationComesFromTheHostsSummonAttack1Node() {
        DataProvider skillWz = hostSkillWz();
        if (skillWz == null) {
            return; // host checkout not adjacent; nothing to read against
        }
        for (int[] expected : EXPECTED_MS) {
            assertEquals(expected[1], BotSummonStrikeAnimation.read(skillWz, expected[0]),
                    "skill " + expected[0] + " must read its own summon/attack1 length");
        }
    }

    @Test
    void unreadableSkillFallsBackInsteadOfNeverStriking() {
        assertEquals(1000, BotSummonStrikeAnimation.read(null, 3111005),
                "a provider that cannot be opened must not yield a zero cooldown");
        // 3111002 is the archer Puppet - deliberately not a bot summon, and its row carries no
        // summon/attack1, so it stands in for a skill the reader must not choke on.
        DataProvider skillWz = hostSkillWz();
        if (skillWz != null) {
            assertTrue(BotSummonStrikeAnimation.read(skillWz, 3111002) > 0,
                    "an unregistered skill must still produce a usable cooldown");
        }
    }

    /** The host's Skill.wz tree through the host's own parser, or null when it is not adjacent. */
    private static DataProvider hostSkillWz() {
        Path wz = Paths.get("../GMS083/gms-server/wz/Skill.wz");
        if (!Files.isDirectory(wz)) {
            return null;
        }
        try {
            return new XMLWZFile(wz);
        } catch (RuntimeException e) {
            return null; // an unreadable tree is the sibling tests' skip case too
        }
    }
}
