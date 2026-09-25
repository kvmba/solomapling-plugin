package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link BotBuffEffects}' per-skill foreign-buff layout dispatch.
 *
 * <p>The v83 client does not decode every {@code GIVE_FOREIGN_BUFF} frame the same way, and it does
 * not decode every buff-mask BIT the host emits. Verified against the v83 (BeiDou) client in IDA:
 * its {@code DecodeStat} switch recognises only 31 fixed mask positions, and the host's
 * {@code DASH2}/{@code DASH}/{@code SPEED_INFUSION} mask bits land OUTSIDE that set — so routing the
 * dash / infusion families through the host's {@code giveForeignPirateBuff} leaves the whole pirate
 * body unparsed (the aura never shows and the mask desynchronises). The plugin therefore remaps
 * those two families onto the SPEED/JUMP mask positions the v83 client does decode (a 4-byte-int
 * field each), and keeps the CANCEL mask in {@code BotAuraState} on the same remapped stats.</p>
 *
 * <p>The {@code WK_CHARGE} family stays on the host's {@code giveForeignWKChargeEffect} frame: the
 * charge position is decoded differently from the generic frame too, and keeping the host frame
 * keeps a bot's charge visually consistent with a real player's.</p>
 *
 * <p>{@code BotBuffEffects} touches {@code Character}/{@code StatEffect}, whose class-init needs a
 * Spring context, so this check reads the source (the same approach {@code BotBuffConfigSourceTest}
 * uses) and pins the mapping itself: every skill id that host {@code StatEffect} special-cases must
 * be named in the dispatch, the remapped stats must be the v83-decodable ones, and the charge
 * builder must actually be called.</p>
 */
class BotBuffEffectsLayoutTest {

    private static final Path EFFECTS = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffEffects.java");
    // The dash / disguise skill-id sets live with their cancel rules in BotAuraState (BotBuffEffects
    // delegates isDash to it), so a host-side id change must be caught across BOTH files.
    private static final Path AURA = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAuraState.java");
    private static final Path HOST_STATEFFECT = Paths.get(
            "../GMS083/gms-server/src/main/java/org/gms/server/StatEffect.java");

    @Test
    void everyHostSpecialCasedFamilyIsDispatched() throws IOException {
        String src = code(read(EFFECTS));
        String aura = code(read(AURA));
        // The dash/infusion families are REMAPPED onto the v83-decodable SPEED/JUMP positions and
        // sent through the generic frame — the host's giveForeignPirateBuff frame carries
        // DASH2/DASH/SPEED_INFUSION mask bits the v83 client has no decode branch for.
        assertFalse(src.contains("PacketCreator.giveForeignPirateBuff("),
                "the dash/infusion family must NOT go through giveForeignPirateBuff (its mask bits "
                        + "are undecodable by the v83 client)");
        assertTrue(src.contains("PacketCreator.giveForeignWKChargeEffect("),
                "the WK_CHARGE family must go through giveForeignWKChargeEffect (extended frame)");
        assertTrue(src.contains("PacketCreator.giveForeignBuff("),
                "the remapped dash/infusion auras and every other buff go through the generic frame");

        // The remap must land on SPEED/JUMP — the only movement-affecting positions the v83 client
        // decodes as 4-byte int fields.
        assertTrue(src.contains("BuffStat.SPEED"), "dash/infusion must remap onto SPEED");
        assertTrue(src.contains("BuffStat.JUMP"), "dash must remap onto JUMP");

        // The dispatch predicates must exist and be used.
        assertTrue(src.contains("isDash("), "isDash must gate the dash family");
        assertTrue(src.contains("isInfusion("), "isInfusion must gate the infusion family");
        assertTrue(src.contains("isWkCharge("), "isWkCharge must gate the charge family");

        // The cancel mask must mirror the remapped show mask, or the client never clears the aura.
        assertTrue(aura.contains("List.of(BuffStat.SPEED, BuffStat.JUMP)"),
                "BotAuraState's dash cancel mask must use the same remapped SPEED/JUMP stats");
    }

    /**
     * The skill-id sets must equal the host's own predicates. Read both sides from source so a
     * future host change (or a new dash-family skill) is caught instead of silently mismatching.
     *
     * <p>Skipped silently when the host checkout is not adjacent.</p>
     */
    @Test
    void dispatchCoversTheSameSkillIdsAsTheHost() throws IOException {
        if (!Files.isRegularFile(HOST_STATEFFECT)) {
            return; // host checkout not adjacent; nothing to validate against
        }
        String host = Files.readString(HOST_STATEFFECT, StandardCharsets.UTF_8);
        String plugin = code(read(EFFECTS)) + "\n" + code(read(AURA));

        List<String> missing = new ArrayList<>();
        for (String family : List.of("isDash", "isInfusion")) {
            for (String constant : constantsComparedIn(host, family)) {
                if (!plugin.contains(constant)) {
                    missing.add(family + " -> " + constant);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "BotBuffEffects/BotAuraState must dispatch every skill the host special-cases: " + missing);
    }

    /**
     * The class constants a host predicate compares {@code sourceid} against, e.g. for
     * {@code isDash()}: {@code Pirate.DASH}, {@code ThunderBreaker.DASH}, …
     */
    private static List<String> constantsComparedIn(String host, String method) {
        int at = host.indexOf("private boolean " + method + "()");
        if (at < 0) {
            return List.of();
        }
        int end = host.indexOf('}', at); // the one-line {@code return ...;} body closes the method
        String body = host.substring(at, end < 0 ? host.length() : end);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(sourceid|p\\.getLeft\\(\\))\\s*==\\s*([A-Za-z0-9_]+\\.[A-Z_0-9]+)").matcher(body);
        while (m.find()) {
            out.add(m.group(2));
        }
        // isWkCharge has no id list (it scans statups); nothing to collect for it.
        return out;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String code(String src) {
        return src.replaceAll("/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
