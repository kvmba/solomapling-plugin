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
 * <p>The v83 client does not decode every {@code GIVE_FOREIGN_BUFF} frame the same way: the host's
 * own {@code StatEffect.applyTo} routes three skill families through extended frames the client
 * reads with extra fields — pirate 疾驰 ({@code isDash}), 极速领域 / 船长的勇士的意志
 * ({@code isInfusion}), and the {@code WK_CHARGE} 元素剑 family ({@code isWkCharge}). Sending the
 * short generic frame for those makes the client parse past the packet's end (the reported
 * 「数据过短」 crash), so the plugin must mirror the host's dispatch rather than blanket-send
 * {@code giveForeignBuff}.</p>
 *
 * <p>{@code BotBuffEffects} touches {@code Character}/{@code StatEffect}, whose class-init needs a
 * Spring context, so this check reads the source (the same approach {@code BotBuffConfigSourceTest}
 * uses) and pins the mapping itself: every skill id that host {@code StatEffect} special-cases must
 * be named in the dispatch, and the pirate/charge builders must actually be called.</p>
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
        // The three extended-frame builders the host itself uses.
        assertTrue(src.contains("PacketCreator.giveForeignPirateBuff("),
                "the dash/infusion family must go through giveForeignPirateBuff (extended frame)");
        assertTrue(src.contains("PacketCreator.giveForeignWKChargeEffect("),
                "the WK_CHARGE family must go through giveForeignWKChargeEffect (extended frame)");
        assertTrue(src.contains("PacketCreator.giveForeignBuff("),
                "the whitelisted generic-frame families keep the generic frame");

        // The dispatch predicates must exist and be used.
        assertTrue(src.contains("isDash("), "isDash must gate the dash family");
        assertTrue(src.contains("isInfusion("), "isInfusion must gate the infusion family");
        assertTrue(src.contains("isWkCharge("), "isWkCharge must gate the charge family");
    }

    /**
     * The generic-forward fallback must be GONE: outside the host's whitelist the host's
     * {@code applyBuffEffect} leaves {@code mbuff} null and broadcasts nothing, so the plugin's
     * {@code auraPacket} must return {@code null} too (the observed「数据非法」 came from the old
     * catch-all forwarding WZ statups the client never legitimately receives). The whitelist
     * itself lives in {@code BotAuraState.isForeignAura} and the on-arrival ledger consults it.
     */
    @Test
    void genericForwardIsWhitelistOnly() throws IOException {
        String src = code(read(EFFECTS));
        String aura = code(read(AURA));

        // The fall-through after the last whitelist branch is a bare null - the old
        // "return PacketCreator.giveForeignBuff(bot.getId(), statups)" forward is forbidden.
        int tail = src.lastIndexOf("return null;");
        assertTrue(tail > src.lastIndexOf("PacketCreator.giveForeignBuff("),
                "auraPacket must END in 'return null;' - no generic forward past the whitelist");
        assertFalse(src.contains("return PacketCreator.giveForeignBuff(bot.getId(), statups);"),
                "the blanket WZ-statup forward must not come back");

        // The replay ledger must consult the same whitelist, so a non-whitelist show can never
        // be re-sent to a fresh observer either.
        assertTrue(aura.contains("isForeignAura(") && aura.contains("SHOWN_AURAS"),
                "BotAuraState must gate the SHOWN_AURAS ledger on isForeignAura");

        // The 橡木伪装 family must keep its dedicated MORPH-1002 frame: the b963f16 whitelist
        // mirror missed it (the host's isMorph() covers every morphId > 0, including the barrel)
        // and the disguise stopped rendering. The frame must be built from the disguise predicate
        // and pinned to the WZ morph 1002, gender-independent (getMorph's +100 rule is 1000/1001/1003 only).
        assertTrue(src.contains("if (BotAuraState.isDisguise(skillId)) {"),
                "the 橡木伪装 family must have its own auraPacket branch");
        assertTrue(src.contains("new Pair<>(BuffStat.MORPH, 1002)"),
                "the disguise frame must carry the barrel's WZ morph 1002");
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
