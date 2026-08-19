package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoloMaplingLanguageConfigTest {

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void defaultIsEnglishPack() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("BotDialoguePack", SoloMaplingLanguageConfig.dialoguePackDirectoryName());
        assertTrue(SoloMaplingLanguageConfig.isDefaultEnglish());
    }

    @Test
    void zhCnUsesLocalizedPack() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals("BotDialoguePack-zh-CN", SoloMaplingLanguageConfig.dialoguePackDirectoryName());
    }

    @Test
    void enAliasUsesDefaultPack() {
        SoloMaplingLanguageConfig.setLanguageTag("en");
        assertEquals("BotDialoguePack", SoloMaplingLanguageConfig.dialoguePackDirectoryName());
    }
}
