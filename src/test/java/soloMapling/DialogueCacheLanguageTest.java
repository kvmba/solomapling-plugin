package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialogue caches must be namespaced by language.
 *
 * <p>The same file name resolves to a different file per language (BotDialoguePack/ vs
 * BotDialoguePack-zh-CN/), and a node present in one pack may be absent from another. Keying the
 * cache without the language tag meant the first lookup won forever: a node first read as Chinese
 * kept serving Chinese on an English client, and a node first looked up under the wrong language
 * cached its "missing" sentinel and stayed missing after the switch.
 */
class DialogueCacheLanguageTest {

    private static final String PATH = "MegaphoneDialogue.yaml";
    private static final String TYPE = "MegaphoneBroadcast";

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
        BotDialogueHandler.invalidateDialogueCache();
    }

    @Test
    void nodeAbsentInEnglishIsFoundAfterSwitchingToChinese() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        // Prime the cache with the miss: the English pack has no BeefCallouts.
        BotDialogueHandler.getDialogueCon(PATH, TYPE, "BeefCallouts");

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        var con = BotDialogueHandler.getDialogueCon(PATH, TYPE, "BeefCallouts");
        assertNotNull(con, "a cached miss must not leak across languages");
        assertTrue(con.getDialogue().size() > 1000,
                "expected the zh-CN beef pool, got " + con.getDialogue().size());
    }

    @Test
    void nodeAbsentInChineseIsHandledAfterSwitchingToEnglish() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        var zh = BotDialogueHandler.getDialogueCon(PATH, TYPE, "BeefCallouts");
        assertNotNull(zh);

        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        var en = BotDialogueHandler.getDialogueCon(PATH, TYPE, "BeefCallouts");
        assertTrue(en == null || en.getDialogue().isEmpty(),
                "English pack has no BeefCallouts; must not inherit the zh-CN node");
    }

    @Test
    void sharedNodeTextDiffersByLanguage() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        var zh = BotDialogueHandler.getDialogueCon(PATH, TYPE, "SocialMessages");
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        var en = BotDialogueHandler.getDialogueCon(PATH, TYPE, "SocialMessages");

        assertNotNull(zh);
        assertNotNull(en);
        assertNotEquals(zh.getDialogue(), en.getDialogue(),
                "the same node must resolve to each language's own text");
    }
}
