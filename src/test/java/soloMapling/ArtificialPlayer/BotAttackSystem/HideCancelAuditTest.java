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
 * Source-level guard for the "an attack breaks the hide" rule (伪装 / 隐身术).
 *
 * <p>Every site that broadcasts a bot attack packet must drop the hide auras first, the way the
 * official client does: the attack key's handler cancels 橡木伪装 before anything else, and the
 * host's own damage handlers cancel 隐身术 on any swing. {@code Character} needs a Spring context
 * so the swing sites cannot be exercised in a plain unit test - the same reason
 * {@code BotBuffConfigSourceTest} reads source - so this pins the call graph instead: every
 * attack-emitting method in the plugin must contain a {@code cancelHidesForAction} call, either
 * directly or in the caller it delegates the swing to.</p>
 *
 * <p>The three {@code BotAttackEffects.*Strike} builders are exempt individually: the attack driver
 * (their only caller) cancels once before dispatching any of them, and a second call inside each
 * builder would be redundant. {@code BotSummonFollower} is exempt by rule: the summon attacks, not
 * the bot, and no summon-owning line (archer / mage / dark knight / pirate turret) can hold a hide
 * aura (橡木伪装 is brawler-line, 隐身术 is thief-line) - nor does the official client cancel a
 * player's hide when the summon strikes.</p>
 */
class HideCancelAuditTest {

    private static final Path SRC = Paths.get("src/main/java");

    /** Methods that broadcast a bot attack packet (the swing sites), keyed by the file holding them. */
    private static final List<SwingSite> SWING_SITES = List.of(
            // The four cosmetic swing helpers - each cancels inside its own body.
            new SwingSite("soloMapling/ArtificialPlayer/BotCommandsPack/BotAttack.java", "basicSwing"),
            new SwingSite("soloMapling/ArtificialPlayer/BotCommandsPack/BotAttack.java", "skillSwing"),
            new SwingSite("soloMapling/ArtificialPlayer/BotCommandsPack/BotAttack.java", "rangedSwing"),
            new SwingSite("soloMapling/ArtificialPlayer/BotCommandsPack/BotAttack.java", "magicSwing"),
            // The attack driver - one cancel covers its melee/ranged/magic strike dispatch.
            new SwingSite("soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java",
                    "private static AttackResult attack("),
            // The energy-charge touch retaliation - a real ENERGY_ATTACK strike of its own.
            new SwingSite("soloMapling/ArtificialPlayer/BotAttackSystem/BotEnergyCharge.java", "strike("),
            // The PQ stage scripts hit reactors without a swing - a reactor hit is still an attack.
            new SwingSite("soloMapling/ArtificialPlayer/PartyQuest/PqActions.java", "hitReactor(")
    );

    @Test
    void everySwingSiteCancelsTheHideAuras() throws IOException {
        List<String> missing = new ArrayList<>();
        for (SwingSite site : SWING_SITES) {
            String body = methodBody(read(SRC.resolve(site.file())), site.method);
            if (body == null) {
                missing.add(site.file() + " has no method matching " + site.method());
                continue;
            }
            if (!body.contains("cancelHidesForAction")
                    && !body.contains("cancelDisguiseForAction")
                    && !body.contains("BotAttack.")) {
                // A site that routes the swing through a BotAttack helper inherits that helper's
                // cancel (e.g. a stage script that swings before hitting the reactor).
                missing.add(site.file() + " " + site.method + " broadcasts a swing without a hide cancel");
            }
        }
        assertTrue(missing.isEmpty(), "every attack site must cancel the hide auras: " + missing);
    }

    @Test
    void theDamageLayerGatesOnTheHideImmunity() throws IOException {
        String contact = read(SRC.resolve("soloMapling/ArtificialPlayer/GCMoveSystem/BotContactDamage.java"));
        assertTrue(contact.contains("BotAuraState.isMonsterImmune(bot)"),
                "tickMobDamage must skip a hidden bot entirely (no touch hit, no debuff)");
        assertTrue(countOccurrences(contact, "BotAuraState.isMonsterImmune") >= 2,
                "applyFallDamage must skip a hidden bot too (fall damage is damage while hidden)");
        String state = read(SRC.resolve("soloMapling/ArtificialPlayer/BotAttackSystem/BotAuraState.java"));
        assertTrue(state.contains("DARK_SIGHT_UP"),
                "BotAuraState must track the 隐身术 aura for the immunity gate");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    /**
     * The source of one method, sliced from its signature to the brace that closes the member.
     * Good enough for these flat helper classes; falls back to "rest of file" so a refactor
     * cannot accidentally make a body look empty.
     */
    private static String methodBody(String src, String signature) {
        int at = src.indexOf(signature);
        if (at < 0) {
            return null;
        }
        int depth = 0;
        boolean opened = false;
        for (int i = src.indexOf('{', at); i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
                opened = true;
            } else if (c == '}') {
                depth--;
                if (opened && depth == 0) {
                    return src.substring(at, i + 1);
                }
            }
        }
        return src.substring(at);
    }

    private record SwingSite(String file, String method) {
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
