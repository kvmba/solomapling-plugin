package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the size and shape of the in-quest chatter pools: how much a run's beats can draw
 * from, in both languages, and that a drawn line is never a raw key.
 *
 * <p>The failure this guards is the quiet kind - a pool that shrinks in one language only,
 * or a beat whose key was renamed, both surface as a bot that simply says less, and nobody
 * files a bug for a bot that stopped being fun. Counting here makes the regression loud.
 */
class PqStageChatTest {

    /** Every quest name the spawn table can put in a lobby, keyed as the message pack names them. */
    private static List<String> spawnedQuestNames() {
        List<String> names = new ArrayList<>();
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            // MagatiaPQ_Z spawns the same bot as MagatiaPQ (one type, two towns); the pack
            // names the quest, not the spawn row.
            names.add(point.name().equals("MagatiaPQ_Z") ? "MagatiaPQ" : point.name());
        }
        names.add(PqRecruitPoints.ORBIS.name());
        return names;
    }

    /** Keys the pack actually defines under {@code pq.chat.<scope>.<beat>[...]}: key -> count. */
    private static Map<String, Integer> poolSizes() {
        java.util.Map<String, Integer> sizes = new java.util.HashMap<>();
        for (String quest : spawnedQuestNames()) {
            for (PartyQuestBot.Beat beat : PartyQuestBot.Beat.values()) {
                int n = poolSize("pq.chat." + quest + "." + beat.name().toLowerCase());
                if (n > 0) {
                    sizes.put("pq.chat." + quest + "." + beat.name().toLowerCase(), n);
                }
            }
        }
        for (PartyQuestBot.Beat beat : PartyQuestBot.Beat.values()) {
            String base = "pq.chat.generic." + beat.name().toLowerCase();
            int n = poolSize(base);
            sizes.put(base, n);
        }
        return sizes;
    }

    /** Lines in one numbered pool (pools run from {@code <base>.1}); 0 when absent. */
    private static int poolSize(String base) {
        int n = 0;
        while (!BotMessages.get(base + "." + (n + 1)).equals(base + "." + (n + 1))) {
            n++;
        }
        return n;
    }

    private static int sumFor(java.util.Map<String, Integer> sizes, String scope) {
        return sizes.entrySet().stream()
                .filter(e -> e.getKey().startsWith("pq.chat." + scope + "."))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
        BotMessages.invalidate();
    }

    @Test
    void genericPoolShipsAtLeastTwoHundredLines() {
        Map<String, Integer> sizes = poolSizes();
        int total = sumFor(sizes, "generic");
        assertTrue(total >= 200, "generic pools must ship at least 200 lines, found " + total);
        // Every beat must be drawable: a run with a silent beat is a run with a dead beat.
        for (PartyQuestBot.Beat beat : PartyQuestBot.Beat.values()) {
            Integer count = sizes.get("pq.chat.generic." + beat.name().toLowerCase());
            assertNotNull(count, "generic pool missing for beat " + beat);
            assertTrue(count >= 10, "generic pool for " + beat + " is thin: " + count);
        }
    }

    @Test
    void everySpawnedQuestShipsAnExclusivePoolOfFifty() {
        Map<String, Integer> sizes = poolSizes();
        for (String quest : spawnedQuestNames()) {
            // Orbis is played by the OPQ orchestrator with its own phase chatter, not this
            // pool; its lobby shout is covered by PqRecruitMessagesTest.
            if (quest.equals("OrbisPQ")) {
                continue;
            }
            int total = sumFor(sizes, quest);
            assertEquals(50, total, quest + " exclusive pool must ship 50 lines");
        }
    }

    @Test
    void poolsAreLocalized() {
        Map<String, Integer> english = poolSizes();

        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotMessages.invalidate();
        Map<String, Integer> chinese = poolSizes();

        assertEquals(english.keySet(), chinese.keySet(),
                "the two packs must define the same pq.chat pools");
        for (String key : english.keySet()) {
            assertEquals(english.get(key), chinese.get(key),
                    key + " has different pool sizes per language");
        }
        assertNotEquals(0, english.size());
    }

    @Test
    void lineNeverLeaksARawKey() {
        for (PartyQuestBot.Beat beat : PartyQuestBot.Beat.values()) {
            String named = PqStageChat.line("HenesysPQ", beat);
            assertNotNull(named, beat + " must draw a line for a pool-bearing quest");
            assertFalse(named.contains("pq.chat."), named);

            String generic = PqStageChat.line(null, beat);
            assertNotNull(generic, beat + " must draw from the generic pool for a nameless bot");
            assertFalse(generic.contains("pq.chat."), generic);
        }
    }

    @Test
    void anUnknownQuestFallsBackToTheGenericPool() {
        String line = PqStageChat.line("NoSuchQuestPQ", PartyQuestBot.Beat.START);
        assertNotNull(line);
        assertFalse(line.isBlank());
        assertFalse(line.contains("pq.chat."));
    }
}
