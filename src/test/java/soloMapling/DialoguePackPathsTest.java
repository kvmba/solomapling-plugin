package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.DialoguePackPaths;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.Reader;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialoguePackPathsTest {

    @AfterEach
    void resetLanguage() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    private static String readHead(Reader reader, int maxChars) throws Exception {
        char[] buf = new char[maxChars];
        int n = reader.read(buf);
        return new String(Arrays.copyOf(buf, Math.max(n, 0)));
    }

    @Test
    void englishPackOpensDefaultDialogue() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        try (Reader reader = DialoguePackPaths.openDialogueReader("TownChatterDialogue.yaml")) {
            String head = readHead(reader, 200);
            assertTrue(head.contains("exchanges") || head.contains("Generic town"));
        }
    }

    @Test
    void chinesePackOpensLocalizedDialogue() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        try (Reader reader = DialoguePackPaths.openDialogueReader("TownChatterDialogue.yaml")) {
            String head = readHead(reader, 400);
            assertTrue(head.contains("exchanges"));
            assertTrue(head.contains("天空之城") || head.contains("城镇"));
        }
    }

    @Test
    void resolveFsPathFindsLocalizedFile() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        String path = DialoguePackPaths.resolveFsPath("TrainingBotDialogue.yaml");
        assertNotNull(path);
        assertTrue(path.contains("BotDialoguePack-zh-CN"));
    }
}
