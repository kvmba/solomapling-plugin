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

    private static List<String> shippedPacks() {
        return Arrays.asList(
                "BlackjackDealerBotDialogue.yaml",
                "DropGameBotDialogue.yaml",
                "DropGameSpectatorDialogue.yaml",
                "FMBotDialogue.yaml",
                "FollowerBotDialogue.yaml",
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
