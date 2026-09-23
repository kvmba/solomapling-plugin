package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level guards for {@link BotBuffConfig}.
 *
 * <p>{@code BotBuffConfig}'s static initialiser touches {@code org.gms.client.Job}, whose class-init
 * needs a Spring context, so the registry cannot be initialised in a plain unit test. These checks
 * therefore read the source (the same approach {@code JobNameLocalizationTest} uses) and pin the two
 * failure modes that are invisible at compile time:</p>
 *
 * <ol>
 *   <li><b>Duplicate job key.</b> {@code BUFFS_BY_JOB} is an {@code EnumMap}, so a second
 *       {@code put(Job.X, …)} silently replaces the first — dropping every buff it carried. A job
 *       must be registered exactly once across all four job tiers.</li>
 *   <li><b>A registered id that is not a v83 skill.</b> The host's own {@code Warrior.IRON_BODY}
 *       constant reads {@code 1000003}, which is NOT in the v83 Skill.wz — broadcasting it crashes
 *       an observing client, which is why the config names {@code 1001003} by hand. Every other
 *       constant on a {@code put(...)} line must resolve to an id that exists in Skill.wz.</li>
 * </ol>
 */
class BotBuffConfigSourceTest {

    private static final Path CONFIG = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffConfig.java");

    // put(Job.NAME, ... ) — captures the job constant.
    private static final Pattern PUT_JOB = Pattern.compile("put\\(\\s*Job\\.([A-Z_]+)\\s*,");
    // A class constant on a put line: e.g. Hero.STANCE. Excludes Job.X.
    private static final Pattern CONST = Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\.([A-Z][A-Z0-9_]+)\\b");

    @Test
    void eachJobIsRegisteredAtMostOnce() throws IOException {
        String src = read(CONFIG);
        Map<String, Integer> counts = new TreeMap<>();
        Matcher m = PUT_JOB.matcher(code(src));
        while (m.find()) {
            counts.merge(m.group(1), 1, Integer::sum);
        }
        List<String> dupes = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                dupes.add(e.getKey() + " x" + e.getValue());
            }
        }
        assertTrue(dupes.isEmpty(),
                "a job registered twice loses the first entry (EnumMap overwrite): " + dupes);
        assertFalse(counts.isEmpty(), "expected the registry to register some jobs");
    }

    @Test
    void ironBodyIsTheWzSkillNotTheBrokenHostConstant() throws IOException {
        String src = code(read(CONFIG));
        // The 1st-job warrior line must use the hand-named id, never the host's 1000003 constant.
        assertTrue(src.contains("IRON_BODY"), "the warrior 1st-job buff (圣甲术) must stay registered");
        assertTrue(src.contains("private static final int IRON_BODY = 1001003;"),
                "IRON_BODY must be the wz-valid 1001003, not the host constant 1000003");
    }

    /**
     * Every class constant on a {@code put(...)} line must (a) exist among the host's skill
     * constants and (b) resolve to an id that IS in the v83 Skill.wz. An id that is not in Skill.wz
     * crashes an observing client when its buff is broadcast - the exact bug the Iron Body override
     * exists to avoid, generalised to the whole registry.
     *
     * <p>Skipped silently when the host checkout is not adjacent (never fails a lone plugin build).</p>
     */
    @Test
    void everyRegisteredConstantResolvesToAWzSkill() throws IOException {
        Path hostRoot = Paths.get("../GMS083/gms-server");
        Path constDir = hostRoot.resolve("src/main/java/org/gms/constants/skills");
        Path wzDir = hostRoot.resolve("wz/Skill.wz");
        if (!Files.isDirectory(constDir) || !Files.isDirectory(wzDir)) {
            return; // host checkout not adjacent; nothing to validate against
        }

        Map<String, Integer> constants = loadHostConstants(constDir);
        java.util.Set<Integer> wzSkills = loadWzSkillIds(wzDir);

        String src = code(read(CONFIG));
        List<String> problems = new ArrayList<>();
        for (String line : src.split("\n")) {
            if (!line.contains("put(")) {
                continue; // only the registry entries, never prose or unrelated code
            }
            Matcher m = CONST.matcher(line);
            while (m.find()) {
                String cls = m.group(1);
                String name = m.group(2);
                if (cls.equals("Job") || cls.equals("IRON_BODY")) {
                    continue; // Job.X and the hand-named IRON_BODY are handled explicitly
                }
                Integer id = constants.get(cls + "." + name);
                if (id == null) {
                    problems.add(cls + "." + name + " is not a host skill constant");
                } else if (!wzSkills.contains(id)) {
                    problems.add(cls + "." + name + " = " + id + " is not in Skill.wz");
                }
            }
        }
        assertTrue(problems.isEmpty(), "registered buffs must be real wz skills: " + problems);
    }

    /** {@code Cls.NAME -> id} for every {@code public static final int} in the host skills package. */
    private static Map<String, Integer> loadHostConstants(Path dir) throws IOException {
        Pattern p = Pattern.compile("public\\s+static\\s+final\\s+int\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*(\\d+)\\s*;");
        Map<String, Integer> out = new TreeMap<>();
        try (var stream = Files.list(dir)) {
            for (Path f : stream.filter(p2 -> p2.toString().endsWith(".java")).toList()) {
                String cls = f.getFileName().toString().replace(".java", "");
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) {
                    out.put(cls + "." + m.group(1), Integer.parseInt(m.group(2)));
                }
            }
        }
        return out;
    }

    /** Every numeric skill id declared under a {@code skill} node in the host's Skill.wz XML. */
    private static java.util.Set<Integer> loadWzSkillIds(Path wzDir) throws IOException {
        Pattern idPattern = Pattern.compile("<imgdir name=\"(\\d{4,})\">");
        java.util.Set<Integer> out = new java.util.HashSet<>();
        try (var stream = Files.list(wzDir)) {
            for (Path f : stream.filter(p2 -> p2.toString().endsWith(".img.xml")).toList()) {
                Matcher m = idPattern.matcher(Files.readString(f));
                while (m.find()) {
                    out.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        return out;
    }

    /** Strips line/block comments so assertions match real code, not prose about it. */
    private static String code(String src) {
        StringBuilder out = new StringBuilder();
        boolean block = false;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (block) {
                if (t.contains("*/")) {
                    block = false;
                    t = t.substring(t.indexOf("*/") + 2).trim();
                } else {
                    continue;
                }
            }
            if (t.startsWith("/*")) {
                block = !t.contains("*/");
                continue;
            }
            if (t.startsWith("//")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "expected file at " + p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
