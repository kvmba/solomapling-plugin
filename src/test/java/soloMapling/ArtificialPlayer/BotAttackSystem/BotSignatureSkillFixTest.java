package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level guards for the four signature-skill fixes:
 *
 * <ol>
 *   <li><b>斗气集中 never re-shown by the buff sweep.</b> The host pins the COMBO statup at 1
 *       (StatEffect's COMBO case), while the live ring is BotComboOrb's broadcast. A sweep re-show
 *       would slam an observer's ring back to 1 every recast.</li>
 *   <li><b>Final-attack prop matches the WZ.</b> The top level of every 终极 skill carries
 *       {@code prop 60} (read x/100, as StatEffect.loadFromData does) — verified against the
 *       adjacent host checkout when present.</li>
 *   <li><b>圣域 carries its WZ cooltime.</b> The skill's own 20s max-level cooltime must throttle
 *       the aoe slot, not the plain swing cadence.</li>
 *   <li><b>Mist / Web derive their level from character level.</b> Ambient bots never register
 *       skills, so a {@code getSkillLevel} gate would lock the whole job out of its signature
 *       cast (the derivation mirrors BotShadowMeso's SP-budget rule).</li>
 * </ol>
 */
class BotSignatureSkillFixTest {

    private static final Path SRC = Paths.get("src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void theBuffSweepNeverReshowsTheComboRing() throws IOException {
        String src = read(SRC.resolve("BotBuffDriver.java"));
        assertTrue(src.contains("import org.gms.constants.skills.Crusader;"),
                "the sweep must name Crusader.COMBO to skip it");
        assertTrue(src.contains("skillId == Crusader.COMBO"),
                "castDueBuffs must skip the combo aura - its wire value is BotComboOrb's, "
                        + "the sweep would broadcast the WZ statup (1) over the live ring");
    }

    /** The largest {@code prop} across the level table of one skill img. */
    private static int wzMaxProp(Path skillImg, String skillId) throws IOException {
        String src = read(skillImg);
        Matcher body = Pattern.compile(
                "<imgdir name=\"" + skillId + "\">(?s).*?</imgdir>\\s*</imgdir>").matcher(src);
        assertTrue(body.find(), skillId + " not found in " + skillImg);
        Matcher props = Pattern.compile("name=\"prop\" value=\"(\\d+)\"").matcher(body.group());
        int max = -1;
        while (props.find()) {
            max = Math.max(max, Integer.parseInt(props.group(1)));
        }
        return max;
    }

    @Test
    void finalAttackPropMatchesTheWz() throws IOException {
        String src = read(SRC.resolve("BotFinalAttack.java"));
        Matcher m = Pattern.compile("PROP_MAX\\s*=\\s*([0-9.]+).*?PROP_BOW_MAX\\s*=\\s*([0-9.]+)", Pattern.DOTALL)
                .matcher(src);
        assertTrue(m.find(), "PROP_MAX / PROP_BOW_MAX constants must stay declared");

        Path wz = Paths.get("../GMS083/gms-server/wz/Skill.wz");
        if (!Files.isDirectory(wz)) {
            return; // host checkout not adjacent; the literal guard above still ran
        }
        int weaponProp = wzMaxProp(wz.resolve("110.img.xml"), "1100002");   // 终极剑
        int bowProp = wzMaxProp(wz.resolve("310.img.xml"), "3100001");      // 终极弓
        assertEquals(weaponProp / 100.0, Double.parseDouble(m.group(1)),
                "PROP_MAX must equal the 终极剑 max-level prop (" + weaponProp + ")");
        assertEquals(bowProp / 100.0, Double.parseDouble(m.group(2)),
                "PROP_BOW_MAX must equal the 终极弓 max-level prop (" + bowProp + ")");
    }

    @Test
    void heavensHammerSitsInTheThrottledUltimateSlot() throws IOException {
        String config = read(SRC.resolve("BotAttackConfig.java"));
        // The paladin line must register 圣域 ONLY in the ultimate slot (which owns the driver's
        // separate 25s full-map-nuke cooldown) and leave the aoe slot null - the pack attack then
        // falls back through the lineage to the Warrior-line Slash Blast (the paladin's own 120/121
        // ancestors register no AoE). Registering 圣域 as the sustained AoE re-fired a
        // true-player-20s-cooldown skill every swing cadence.
        Matcher line = Pattern.compile("put\\(Job\\.PALADIN,(.*?)\\);", Pattern.DOTALL).matcher(config);
        assertTrue(line.find(), "the paladin attack line must stay registered");
        String slots = line.group(1);
        long count = java.util.Arrays.stream(slots.split(",\\s*(?=[A-Za-z])"))
                .filter(s -> s.contains("HEAVENS_HAMMER")).count();
        assertEquals(1, count, "圣域 must appear exactly once - in the ultimate slot");
        assertTrue(slots.contains("null"),
                "the paladin aoe slot must stay null (falls back to the lineage Slash Blast)");

        Path wz = Paths.get("../GMS083/gms-server/wz/Skill.wz");
        if (Files.isDirectory(wz)) {
            String img = read(wz.resolve("122.img.xml"));
            Matcher body = Pattern.compile("<imgdir name=\"1221011\">(?s).*?</imgdir>\\s*</imgdir>")
                    .matcher(img);
            assertTrue(body.find(), "1221011 not found");
            Matcher ct = Pattern.compile("name=\"cooltime\" value=\"(\\d+)\"").matcher(body.group());
            int max = -1;
            while (ct.find()) {
                max = Math.max(max, Integer.parseInt(ct.group(1)));
            }
            assertTrue(max >= 10, "the WZ cooltime grounding this decision disappeared: " + max);
        }
    }

    @Test
    void mistAndWebLevelDerivesFromCharacterLevel() throws IOException {
        for (String file : new String[]{"BotGroundMists.java", "BotShadowWeb.java"}) {
            String src = read(SRC.resolve(file));
            assertFalse(src.contains("bot.getSkillLevel"),
                    file + " must not gate on getSkillLevel - ambient bots never register skills, "
                            + "so the gate locks the whole job out of its signature cast");
            assertTrue(src.contains("(bot.getLevel() - 70) * 3"),
                    file + " must derive the cast level from the SP budget (3rd job at 70, 3 SP/level), "
                            + "mirroring BotShadowMeso");
        }
    }
}
