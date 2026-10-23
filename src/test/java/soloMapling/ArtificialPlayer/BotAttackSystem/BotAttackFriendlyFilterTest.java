package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every scan that can damage a monster must apply {@link BotTargetRules}.
 *
 * <p>The attack layer does not have one target scan. The driver's {@code seek()} serves ordinary
 * swings, but the Pickpocket bomb, Shadow Meso, Shadow Web, Poison Mist and each summon strike
 * pick their own candidates and land real damage through
 * {@code BotAttackEffects.applyExternalHit}. An earlier fix gated only the driver, which reads as
 * "friendly mobs are safe" while a Chief Bandit's Money Explosion could still kill the HPQ Moon
 * Bunny - the exact failure the gate exists to prevent.
 *
 * <p>Kept source-level for the same reason the sibling guards are: {@code Character} needs a
 * Spring context, and a full {@code MapleMap} cannot be constructed in a unit test. The check is
 * structural - each named scan must ask the shared rule before adding a candidate - and the rule
 * itself is pinned behaviourally in {@link BotTargetRulesTest}.
 */
class BotAttackFriendlyFilterTest {

    private static final String ATTACK = "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/";
    private static final String SUMMON = "src/main/java/soloMapling/ArtificialPlayer/BotSummonSystem/";

    /**
     * Every scan in the codebase that both selects monsters and applies damage from that choice.
     * Named explicitly (not discovered by a walk) so adding a quest bot cannot silently widen the
     * set: a new damage scan is a deliberate edit here, with the gate it needs.
     */
    private static final List<String> DAMAGING_SCANS = List.of(
            ATTACK + "BotAttackDriver.java",        // AUTO / forced swings, the seek scan
            ATTACK + "BotMesoBomb.java",            // 金钱炸弹 mobsInBox
            ATTACK + "BotShadowMeso.java",          // 金钱投掷
            ATTACK + "BotShadowWeb.java",           // 蛛网
            ATTACK + "BotGroundMists.java",         // 毒雾
            SUMMON + "BotSummonFollower.java");     // every summon strike and turret anchor

    @Test
    void everyDamagingScanAsksTheSharedRule() throws IOException {
        for (String file : DAMAGING_SCANS) {
            String src = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            assertTrue(src.contains("BotTargetRules.isCombatTarget("),
                    file + " picks monster targets but never asks BotTargetRules - friendly mobs "
                            + "and the dojo clear marker stay attackable from here");
        }
    }

    /** The driver's own scan must gate BEFORE the candidate joins the list. */
    @Test
    void theSeekScanDropsUntargetableMonstersBeforeAdding() throws IOException {
        String seek = methodBody(Files.readString(
                Path.of(ATTACK + "BotAttackDriver.java"), StandardCharsets.UTF_8),
                "private static SeekScan seek(");
        assertTrue(seek.contains("notTargetable(m)"),
                "seek() must reject non-targets before they enter the candidate list");
        assertTrue(seek.indexOf("notTargetable(m)") < seek.indexOf("candidates.add(m)"),
                "the gate has to run BEFORE the candidate is added, not after");
    }

    /**
     * The rule is the engine's own flag plus the one marker id - not a hard-coded mob list. The
     * WZ {@code damagedByMob} parse lives in {@code LifeFactory}, so the flag is what the plugin
     * may read.
     */
    @Test
    void theRuleIsTheWzFlagPlusTheMarker() throws IOException {
        String rules = Files.readString(
                Path.of(ATTACK + "BotTargetRules.java"), StandardCharsets.UTF_8);
        assertTrue(rules.contains("isFriendly()"),
                "the friendly half is the engine's stats flag, not a mob-id list");
        assertTrue(rules.contains("mobId == MobId.TRANSPARENT_ITEM"),
                "the marker must be excluded by id: its WZ row is not friendly");
    }

    /** The chase and the touch scan read the same rule, so the marker cannot be chased or traded with. */
    @Test
    void theChaseAndTouchPathsShareTheRule() throws IOException {
        assertTrue(Files.readString(Path.of(
                        "src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PqActions.java"),
                        StandardCharsets.UTF_8).contains("BotTargetRules.isCombatTarget(m)"),
                "isHuntTarget must not chase the dojo clear marker");
        assertTrue(Files.readString(Path.of(
                        "src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotContactDamage.java"),
                        StandardCharsets.UTF_8).contains("BotTargetRules.isCombatTarget(monster)"),
                "a charged brawler must not retaliate against the marker");
    }

    /** The source from {@code signature} to its closing brace at the same indent. */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertFalse(start < 0, "method not found: " + signature);
        int depth = 0;
        int i = src.indexOf('{', start);
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(start, i + 1);
            }
        }
        return src.substring(start);
    }
}
