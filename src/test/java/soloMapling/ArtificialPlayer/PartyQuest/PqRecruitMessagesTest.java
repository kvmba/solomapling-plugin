package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the two things that decide whether a quest lobby looks alive: the recruit name each
 * quest shouts, and whether the shout is localized at all.
 *
 * <p>The failure this guards is silent by construction - a missing name resolves to the raw
 * key ("pq.recruit.name.HenesysPQ") or an empty string, and a bot says it into a lobby full of
 * players without anything throwing. The only signal is the chat line itself.
 *
 * <p>Every quest spawned into a lobby by {@link PqBotSpawner} must have a name, because the
 * spawner is what puts a bot in front of a player in the first place.
 */
class PqRecruitMessagesTest {

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
        BotMessages.invalidate();
    }

    /**
     * The quest names a bot is actually spawned under, read from the spawn table itself.
     *
     * <p>Deliberately not a hand-kept list: {@code PqBotSpawner.spawnFor} keys its bot type on
     * {@link PqRecruitPoints.Point#name()}, so walking that table is what proves the two sides
     * agree. A quest added to the table without a recruit name fails here rather than shouting a
     * raw key at players.
     */
    private static java.util.List<String> spawnedQuestNames() {
        var names = new java.util.ArrayList<String>();
        for (var point : PqRecruitPoints.ALL) {
            names.add(point.name());
        }
        names.add(PqRecruitPoints.ORBIS.name()); // spawned by EnvironmentManager, same naming
        return names;
    }

    /**
     * The {@code MagatiaPQ_Z} row spawns the same bot as {@code MagatiaPQ} (one type, two towns),
     * so its shout resolves through the shared key. Kept explicit rather than special-cased in
     * the generator: the table names the row, the message pack names the quest.
     */
    private static String messageKeyFor(String spawnName) {
        return spawnName.equals("MagatiaPQ_Z") ? "MagatiaPQ" : spawnName;
    }

    @Test
    void everySpawnedQuestHasARecruitName() {
        for (String quest : spawnedQuestNames()) {
            String key = "pq.recruit.name." + messageKeyFor(quest);
            // Deliberately checks the PACK, not the generator's output: the generator has a
            // generic fallback ("PQ"), so a missing name would still produce a plausible shout
            // and the mistake would only be visible as the wrong quest being advertised.
            // BotMessages.get returns the key itself on a total miss, hence the comparison.
            assertNotEquals(key, BotMessages.get(key),
                    quest + " has no recruit name in the message pack");
            assertFalse(BotMessages.get(key).isBlank(), quest + " has a blank recruit name");
        }
    }

    @Test
    void namesAreLocalized() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotMessages.invalidate();
        String chinese = PqRecruitMessages.generateRecruitMessage("HenesysPQ", 42, "战士");

        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        BotMessages.invalidate();
        String english = PqRecruitMessages.generateRecruitMessage("HenesysPQ", 42, "Warrior");

        // The prefix lists are localized too, so the two must not come out identical.
        assertNotEquals(chinese, english);
        // The Chinese name for the quest, from its own event script's title.
        assertTrue(chinese.contains("迎月花山丘"), chinese);
        assertTrue(english.contains("HPQ"), english);
    }

    @Test
    void anUnknownQuestFallsBackInsteadOfLeakingAKey() {
        String line = PqRecruitMessages.generateRecruitMessage("NoSuchQuestPQ", 42, "Warrior");

        assertFalse(line.contains("pq.recruit.name."), line);
        assertFalse(line.isBlank(), line);
    }
}
