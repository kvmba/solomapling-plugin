package soloMapling.ArtificialPlayer;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards DialogueConstructor against the mixed text/emote tagging used across the dialogue packs.
 *
 * <p>A node's "text" list mixes plain strings with {line, emote} maps. The per-line emote list is
 * therefore sparse: a plain string carries no override and stores a null entry. That used to blow up
 * in the constructor, which called {@code List.copyOf} on it - ImmutableCollections rejects null
 * elements - so every node with at least one tagged line was unbuildable and threw an NPE on every
 * bot tick that touched it (e.g. FMBot purchasing from a shop).
 */
class BotDialogueConstructorTest {

    @AfterEach
    void reset() {
        BotDialogueHandler.invalidateDialogueCache();
        BotDialogueHandler.invalidateDialogueNodes();
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    /** Sparse overrides (exactly the shape parseTextEntries produces) must copy cleanly. */
    @Test
    void sparseLineEmotesAreAccepted() {
        List<String> lines = Arrays.asList("ty", "nice", "THERE IT IS.");
        List<List<Integer>> lineEmotes = Arrays.asList(null, null, Collections.singletonList(2));

        BotDialogueHandler.DialogueConstructor con =
                new BotDialogueHandler.DialogueConstructor(lines, Arrays.asList(2, 3, 5), lineEmotes, 1000L);

        assertEquals(lines, con.getDialogue());
        assertEquals(2, con.getEmoteForIndex(2)); // tagged line keeps its own emote
        for (int i = 0; i < 2; i++) {
            assertTrue(con.getEmotes().contains(con.getEmoteForIndex(i))); // untagged -> palette
        }
    }

    /** A null outer list still means "no per-line overrides at all". */
    @Test
    void nullLineEmotesStayNull() {
        BotDialogueHandler.DialogueConstructor con =
                new BotDialogueHandler.DialogueConstructor(Collections.singletonList("hi"), null, null, 0L);

        assertEquals(0, con.getEmote());
        assertEquals(0, con.getEmoteForIndex(0));
    }

    /** The caller's list must not stay aliased to the instance (it is cached and shared). */
    @Test
    void lineEmotesAreCopiedDefensively() {
        List<List<Integer>> lineEmotes = new ArrayList<>();
        lineEmotes.add(null);
        lineEmotes.add(Collections.singletonList(4));

        BotDialogueHandler.DialogueConstructor con =
                new BotDialogueHandler.DialogueConstructor(Arrays.asList("a", "b"), null, lineEmotes, 0L);

        lineEmotes.set(1, Collections.singletonList(9));
        assertEquals(4, con.getEmoteForIndex(1));
    }

    /** withDialogue must carry the sparse overrides over to the copy. */
    @Test
    void withDialoguePreservesSparseOverrides() {
        List<List<Integer>> lineEmotes = Arrays.asList(Collections.singletonList(6), null);
        BotDialogueHandler.DialogueConstructor con =
                new BotDialogueHandler.DialogueConstructor(Arrays.asList("a", "b"), null, lineEmotes, 0L);

        BotDialogueHandler.DialogueConstructor copy = con.withDialogue(Collections.singletonList("only"));

        assertEquals(6, copy.getEmoteForIndex(0));
        assertEquals(2, con.getDialogue().size()); // original untouched
    }

    /** The FM node that triggered the crash must build and be fully resolvable. */
    @Test
    void purchaseItemNodeBuilds() {
        BotDialogueHandler.DialogueConstructor con =
                BotDialogueHandler.getDialogueCon("FMBotDialogue.yaml", "FMBot", "PurchaseItem");

        assertNotNull(con);
        assertFalse(con.getDialogue().isEmpty());
        for (int i = 0; i < con.getDialogue().size(); i++) {
            assertNotNull(con.getDialogue().get(i));
            assertNotNull(con.getEmoteForIndex(i));
        }
    }

    @Test
    void purchaseItemNodeBuildsInChinese() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotDialogueHandler.DialogueConstructor con =
                BotDialogueHandler.getDialogueCon("FMBotDialogue.yaml", "FMBot", "PurchaseItem");

        assertNotNull(con);
        assertFalse(con.getDialogue().isEmpty());
    }

    /**
     * Every node of every shipped dialogue pack must build, in both languages. Before the fix, any
     * node mixing plain strings with tagged lines threw NPE from {@code List.copyOf}.
     */
    @Test
    void everyShippedDialogueNodeBuilds() throws Exception {
        for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(languageTag);
            for (String pack : shippedPacks()) {
                for (String[] typeAndNode : readBotTypesAndNodes(pack)) {
                    String botType = typeAndNode[0];
                    String node = typeAndNode[1];
                    try {
                        BotDialogueHandler.getDialogueCon(pack, botType, node);
                    } catch (RuntimeException e) {
                        throw new AssertionError(languageTag + " " + pack + "/" + botType + "/" + node
                                + " failed to build: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * The GachaBot's bait act speaks from the Taunt node on a fraction of its sprays and the Nudge
     * node when it shuffles to a new spot. A pack that dropped either node (or a language that forgot
     * it) would silently mute those beats, so both languages must ship a healthy pool.
     */
    @Test
    void gachaDialogueNodesArePresentInBothLanguages() {
        for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(languageTag);
            for (String node : Arrays.asList("Taunt", "Nudge")) {
                BotDialogueHandler.DialogueConstructor con =
                        BotDialogueHandler.getDialogueCon("GachaBotDialogue.yaml", "GachaBot", node);
                assertNotNull(con, languageTag + " GachaBot " + node + " node missing");
                List<String> lines = con.getDialogue();
                assertFalse(lines.isEmpty(), languageTag + " GachaBot " + node + " node has no lines");
                assertTrue(lines.size() >= 100,
                        languageTag + " GachaBot " + node + " node should carry >= 100 lines, got " + lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    assertNotNull(lines.get(i), languageTag + " " + node + " line " + i + " is null");
                    assertFalse(lines.get(i).isBlank(), languageTag + " " + node + " line " + i + " is blank");
                    assertNotNull(con.getEmoteForIndex(i), languageTag + " " + node + " line " + i + " has no emote");
                }
            }
        }
    }

    /**
     * The shared {@code TradeDecline} pool (SocialBot pack) is what a NON-trading bot says when a
     * player invites it to trade: after a beat, it speaks one of these and declines, instead of
     * leaving the invite window open for ~3 minutes. The brief asked for at least a hundred lines so
     * bots never sound like a loop; both languages must ship them, and every line must stay short
     * enough to read in a chat bubble.
     */
    @Test
    void tradeDeclinePoolIsLargeInBothLanguages() {
        for (String languageTag : Arrays.asList("en-US", "zh-CN")) {
            SoloMaplingLanguageConfig.setLanguageTag(languageTag);
            BotDialogueHandler.DialogueConstructor con =
                    BotDialogueHandler.getDialogueCon("SocialBotDialogue.yaml", "SocialBot", "TradeDecline");
            assertNotNull(con, languageTag + " SocialBot TradeDecline node missing");
            List<String> lines = con.getDialogue();
            assertTrue(lines.size() >= 100,
                    languageTag + " TradeDecline should carry >= 100 lines, got " + lines.size());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                assertNotNull(line, languageTag + " TradeDecline line " + i + " is null");
                assertFalse(line.isBlank(), languageTag + " TradeDecline line " + i + " is blank");
                assertTrue(seen.add(line), languageTag + " TradeDecline repeats: " + line);
                assertTrue(line.length() <= 80,
                        languageTag + " TradeDecline line too long (" + line.length() + "): " + line);
                assertNotNull(con.getEmoteForIndex(i), languageTag + " TradeDecline line " + i + " has no emote");
            }
        }
    }

    private static List<String> shippedPacks() {
        return Arrays.asList(
                "BlackjackDealerBotDialogue.yaml",
                "DropGameBotDialogue.yaml",
                "DropGameSpectatorDialogue.yaml",
                "FMBotDialogue.yaml",
                "FollowerBotDialogue.yaml",
                "GachaBotDialogue.yaml",
                "GameZoneHostBotDialogue.yaml",
                "HenesysBotDialogue.yaml",
                "JQBotDialogue.yaml",
                "MegaphoneDialogue.yaml",
                "MerchantBotDialogue.yaml",
                "ScrollingBotDialogue.yaml",
                "ShopOfferDialogue.yaml",
                "SocialBotDialogue.yaml",
                "SocialHotPotatoDialogue.yaml",
                "TrainingBotDialogue.yaml",
                "TutorialBotDialogue.yaml");
    }

    /** Every (botType, node) pair present in a pack, read straight from the YAML. */
    @SuppressWarnings("unchecked")
    private static List<String[]> readBotTypesAndNodes(String pack) throws Exception {
        List<String[]> out = new ArrayList<>();
        try (Reader reader = DialoguePackPaths.openDialogueReader(pack)) {
            Map<String, Object> root = (Map<String, Object>) new YamlReader(reader).read();
            assertNotNull(root, pack + " is empty");
            for (Map.Entry<String, Object> typeEntry : root.entrySet()) {
                if (!(typeEntry.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> nodes = (Map<String, Object>) typeEntry.getValue();
                for (String node : nodes.keySet()) {
                    out.add(new String[]{typeEntry.getKey(), node});
                }
            }
        }
        assertFalse(out.isEmpty(), pack + " exposed no nodes");
        return out;
    }
}
