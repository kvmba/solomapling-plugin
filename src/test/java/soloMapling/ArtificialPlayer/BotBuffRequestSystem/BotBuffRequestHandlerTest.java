package soloMapling.ArtificialPlayer.BotBuffRequestSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trigger parsing for chat-triggered buff requests. Guards the two failure modes that matter:
 * a generic ask ("buff" / "加buff" / "+" / "+++") being missed, and ordinary chat being misread
 * as one. Specific buff names are intentionally NOT triggers anymore.
 */
class BotBuffRequestHandlerTest {

    @Test
    void genericTriggersAreRecognized() {
        String[] lines = {
                "buff", "BUFF", "buffs", "加buff", "加个buff", "给我加buff", "来个buff",
                "上个buff", "上buff", "+", "＋", "++", "+++", "＋＋＋", "+ +++", "buff pls",
                "请加buff", "加一个buff", "+++++", "+buff", "++buff", "+BUFF", "＋+buff", "＋加buff"
        };
        for (String line : lines) {
            assertTrue(BotBuffRequestHandler.isBuffRequest(line.trim().toLowerCase()),
                    "should be a request: " + line);
        }
    }

    @Test
    void specificBuffNamesAreNoLongerTriggers() {
        String[] lines = {"hs", "hs pls", "holy symbol", "mw", "maple warrior", "hb", "bless", "haste", "se"};
        for (String line : lines) {
            assertFalse(BotBuffRequestHandler.isBuffRequest(line.toLowerCase()),
                    "should NOT be a request anymore: " + line);
        }
    }

    @Test
    void unrelatedChatIsNotARequest() {
        String[] lines = {"where is the boss", "让我打个怪", "how are you", "123", "buffalo", "加血", ""};
        for (String line : lines) {
            assertFalse(BotBuffRequestHandler.isBuffRequest(line.toLowerCase()),
                    "should NOT be a request: " + line);
        }
    }
}
