package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.LocalizedResources;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalizedResourcesTest {

    private static final String PARENT = "FreeMarket/";
    private static final String PACK = "FMNameDesc";

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void englishUsesDefaultWordList() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("FreeMarket/FMNameDesc/thiefDesc.txt",
                LocalizedResources.resolve(PARENT, PACK, "thiefDesc.txt"));
    }

    @Test
    void chineseUsesLocalizedWordList() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals("FreeMarket/FMNameDesc-zh-CN/thiefDesc.txt",
                LocalizedResources.resolve(PARENT, PACK, "thiefDesc.txt"));
    }

    @Test
    void untranslatedListFallsBackToEnglish() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals("FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt",
                LocalizedResources.resolve(PARENT, PACK, "randomRealMaplestoryIGNs.txt"));
    }

    @Test
    void missingFileResolvesToNull() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertNull(LocalizedResources.resolve(PARENT, PACK, "doesNotExist.txt"));
    }
}
