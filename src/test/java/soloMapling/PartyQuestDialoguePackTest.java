package soloMapling;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.DialoguePackPaths;
import soloMapling.ArtificialPlayer.SocialIntent;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The party-quest lobby pack, {@code PartyQuestBotDialogue.yaml}.
 *
 * <p>Every PQ type points {@code dialoguePath} at this one file instead of a per-quest file that
 * never existed. A missing file used to surface at runtime as {@code [BotDialogueHandler] failed
 * to load <n>PQBotDialogue.yaml} on the first player who spoke to a lobby bot, and the bot silently
 * borrowed the shared social pool. These tests are the guard that the pack exists, covers every
 * node a {@code PartyQuestBot} can actually ask for, and stays deep enough that a bot standing in a
 * lobby for an evening does not start repeating itself.
 */
class PartyQuestDialoguePackTest {

    private static final String PACK = "PartyQuestBotDialogue.yaml";
    private static final String SHARED_BLOCK = "PartyQuestBot";

    /** Minimum lines per node, per language. */
    private static final int MIN_LINES = 50;

    /**
     * The nodes a PartyQuestBot actually asks its own pack for: the two recruit answers
     * ({@code PartyQuestBot.speakNode}), the trade refusal ({@code BotSM.tradeDeclineLine}), and
     * one node per {@link SocialIntent} ({@code BotSM.respondSocial}).
     */
    private static List<String> requiredNodes() {
        List<String> nodes = new ArrayList<>(Arrays.asList("PartyAccept", "PartyDecline", "TradeDecline"));
        for (SocialIntent intent : SocialIntent.values()) {
            nodes.add(intent.node());
        }
        return nodes;
    }

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
        BotDialogueHandler.invalidateDialogueCache();
        BotDialogueHandler.invalidateDialogueNodes();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> root() throws Exception {
        try (Reader reader = DialoguePackPaths.openDialogueReader(PACK)) {
            Map<String, Object> root = (Map<String, Object>) new YamlReader(reader).read();
            assertNotNull(root, PACK + " is empty");
            return root;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> linesOf(Map<String, Object> root, String botType, String node) {
        Object typeBlock = root.get(botType);
        assertNotNull(typeBlock, botType + " is not a top-level key of " + PACK);
        Object nodeBlock = ((Map<String, Object>) typeBlock).get(node);
        assertNotNull(nodeBlock, node + " missing from " + PACK + "/" + botType);
        Object text = ((Map<String, Object>) nodeBlock).get("text");
        assertNotNull(text, node + " has no text list in " + PACK + "/" + botType);
        return (List<String>) text;
    }

    /**
     * The pack ships, and every botType that points at it resolves. This is the regression the
     * runtime log reported: a path naming a file nobody ever created.
     */
    @Test
    void everyPartyQuestBotTypeResolvesThePack() throws Exception {
        List<String> botTypes = pqBotTypes();
        for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(languageTag);
            for (String botType : botTypes) {
                for (String node : requiredNodes()) {
                    assertNodeLoads(languageTag, botType, node);
                }
            }
        }
    }

    /**
     * Ten to the dozen: a shallow node would repeat inside a single lobby visit. Both languages
     * must carry the full depth, not just the default one.
     */
    @Test
    void everyNodeCarriesAtLeastFiftyLinesInBothLanguages() throws Exception {
        for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(languageTag);
            Map<String, Object> root = root();
            for (String botType : pqBotTypes()) {
                for (String node : requiredNodes()) {
                    List<String> lines = linesOf(root, botType, node);
                    assertTrue(lines.size() >= MIN_LINES,
                            languageTag + " " + botType + "/" + node + " has " + lines.size()
                                    + " lines, expected at least " + MIN_LINES);
                }
            }
        }
    }

    /** No blank line, no duplicate: a repeated line reads as a broken pool, not a deep one. */
    @Test
    void noBlankOrRepeatedLines() throws Exception {
        for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(languageTag);
            Map<String, Object> root = root();
            for (String node : requiredNodes()) {
                List<String> lines = linesOf(root, SHARED_BLOCK, node);
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (String line : lines) {
                    assertNotNull(line, languageTag + " " + node + " has a null line");
                    assertFalse(line.isBlank(), languageTag + " " + node + " has a blank line");
                    assertTrue(seen.add(line), languageTag + " " + node + " repeats: " + line);
                }
            }
        }
    }

    /**
     * The three lobby-answer nodes are spoken with a null player (a recruit shout carries no
     * player, a trade invite may outlive them), so a {TOKEN} there would be unresolvable and the
     * line dropped - a deep pool would silently thin out. The social nodes always get a player
     * and may use tokens.
     */
    @Test
    void nodesSpokenWithoutAPlayerCarryNoTokens() throws Exception {
        for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(languageTag);
            Map<String, Object> root = root();
            for (String node : Arrays.asList("PartyAccept", "PartyDecline", "TradeDecline")) {
                for (String line : linesOf(root, SHARED_BLOCK, node)) {
                    assertFalse(soloMapling.ArtificialPlayer.DialogueContextResolver.hasTokens(line),
                            languageTag + " " + node + " is spoken with no player but carries a token: " + line);
                }
            }
        }
    }

    /** Every botType maps onto the same shared block, so editing one line updates them all. */
    @Test
    void allPartyQuestBotTypesShareOneBlock() throws Exception {
        Map<String, Object> root = root();
        Object shared = root.get(SHARED_BLOCK);
        assertNotNull(shared, SHARED_BLOCK + " block missing from " + PACK);
        for (String botType : pqBotTypes()) {
            assertTrue(root.get(botType) == shared,
                    botType + " must alias the " + SHARED_BLOCK + " block, not carry its own copy");
        }
    }

    /**
     * Every botType that asks for the shared PQ pack, discovered from SOURCE rather than a
     * hand-maintained list.
     *
     * <p>A hardcoded list was the first cut's mistake: adding a seventeenth PQ bot left every
     * test green, because the list simply never mentioned it. Scanning for the assignment makes
     * a new type automatically covered - and, via {@link #noPackAliasIsDead()}, makes a stale
     * alias in the pack fail instead of lingering.
     */
    private static List<String> pqBotTypes() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes");
        List<String> types = new ArrayList<>();
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = java.nio.file.Files.readString(file);
                if (!source.contains("dialoguePath = PARTY_QUEST_DIALOGUE_PATH")) {
                    continue;
                }
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("botType\\s*=\\s*\"([^\"]+)\"").matcher(source);
                if (!m.find()) {
                    throw new AssertionError(file.getFileName() + " points at the shared PQ pack "
                            + "but sets no botType, so no node under it can ever resolve");
                }
                String type = m.group(1);
                if (!types.contains(type)) {
                    types.add(type);
                }
            }
        }
        assertFalse(types.isEmpty(), "no bot type points at the shared PQ pack");
        java.util.Collections.sort(types);
        return types;
    }

    /** Resolves a node through the real loader, asserting it loads. */
    private static void assertNodeLoads(String languageTag, String botType, String node) {
        var con = BotDialogueHandler.getDialogueCon(PACK, botType, node);
        assertNotNull(con, languageTag + " " + botType + "/" + node + " did not load");
        assertFalse(con.getDialogue().isEmpty(),
                languageTag + " " + botType + "/" + node + " loaded but is empty");
    }

    /**
     * The reverse direction: no alias in the pack may name a bot type that does not exist.
     *
     * <p>Without this, deleting a PQ bot (or renaming its {@code botType}) leaves its alias behind
     * and nothing notices - the pack keeps claiming a type no code will ever ask for. Together with
     * {@link #pqBotTypes()} this pins the two lists to each other, so a new bot must gain an alias
     * and a removed bot must lose one.
     */
    @Test
    void noPackAliasIsDead() throws Exception {
        List<String> live = pqBotTypes();
        List<String> aliases = new ArrayList<>();
        Map<String, Object> root = root();
        for (String key : root.keySet()) {
            if (!key.equals(SHARED_BLOCK)) {
                aliases.add(key);
            }
        }
        java.util.Collections.sort(aliases);
        for (String alias : aliases) {
            assertTrue(live.contains(alias),
                    PACK + " has an alias for " + alias + ", but no bot type points at the pack - "
                            + "the alias is dead");
        }
        for (String type : live) {
            assertTrue(aliases.contains(type),
                    type + " points at " + PACK + " but the pack has no block for it, so every "
                            + "node it asks for resolves to nothing");
        }
    }
}
