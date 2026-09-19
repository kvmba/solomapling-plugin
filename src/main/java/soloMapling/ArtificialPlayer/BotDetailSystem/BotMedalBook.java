package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.gms.client.Character;
import org.gms.client.QuestStatus;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLWZData;
import org.gms.server.quest.Quest;
import soloMapling.ArtificialPlayer.BotMedalSystem.BotMedal;
import soloMapling.ArtificialPlayer.BotMedalSystem.BotMedalPool;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Presets the medal collection (勋章收藏) a bot shows in its character-info window.
 *
 * <p>A v83 medal (称号) that is earned by a quest marks the quest completed; the window lists every
 * completed quest with id {@code >= 29000} as a collected medal. Bots wear a medal (see
 * {@code BotMedalSystem}) but held no collection, so the window's medal book was empty.
 *
 * <p>The quest→medal mapping is read once from {@code Quest.wz/Act.img} (the reward item of each
 * {@code 29xxx} quest). Which of those a bot may hold is decided by the *same* rule that gates the
 * medal it wears - {@link BotMedalPool#eligibleFor(Character)} already enforces reqLevel / reqJob /
 * reqStats / reqPop and pool legality, so a job-exclusive or too-high-level medal quest can never
 * appear. The worn medal's own quest is always included, so the title on the bot's head is also in
 * its book.
 *
 * <p>Writes only in-memory quest state via {@code getQuestNAdd(...).setStatus(COMPLETED)} - NOT
 * {@code updateQuestStatus}, which would also award fame and emit packets.
 */
public final class BotMedalBook {

    private static final int QUEST_ID_MIN = 29000;
    private static final int QUEST_ID_MAX = 30000;
    private static final int MEDAL_ID_MIN = 1140000;
    private static final int MEDAL_ID_MAX = 1143000;

    private static final int SALT = 0x4D42_0002;

    private static final int LEVEL_FLOOR = 10;
    private static final int LEVEL_CEIL = 80;

    /** questId → medalId, ascending by quest id. Built once at load. */
    private static volatile Map<Integer, Integer> questMedals = Map.of();
    private static volatile boolean loaded = false;

    private BotMedalBook() {
    }

    /** Read the medal-quest table from WZ once. Never throws. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        long start = System.currentTimeMillis();
        Map<Integer, Integer> found = new LinkedHashMap<>();
        try {
            Path file = WZFiles.QUEST.getFile().resolve("Act.img.xml");
            if (!Files.isRegularFile(file)) {
                System.err.println("[BotMedalBook] Act.img not found at " + file);
                return;
            }
            try (FileInputStream fis = new FileInputStream(file.toFile())) {
                Data act = XMLWZData.parse(fis);
                for (Data quest : act.getChildren()) {
                    int questId;
                    try {
                        questId = Integer.parseInt(quest.getName());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (questId < QUEST_ID_MIN || questId >= QUEST_ID_MAX) {
                        continue;
                    }
                    Integer medalId = rewardMedal(quest);
                    if (medalId != null) {
                        found.put(questId, medalId);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[BotMedalBook] failed to scan medal quests: " + e);
        } finally {
            Map<Integer, Integer> sorted = new LinkedHashMap<>();
            for (Integer q : new TreeSet<>(found.keySet())) {
                sorted.put(q, found.get(q));
            }
            questMedals = Collections.unmodifiableMap(sorted);
            loaded = true;
        }
        System.out.println("[BotMedalBook] Loaded " + questMedals.size() + " medal quests in "
                + (System.currentTimeMillis() - start) + "ms");
    }

    /** The medal item a quest grants (its {@code 1/item/*} reward), or null. */
    private static Integer rewardMedal(Data quest) {
        Data status1 = quest.getChildByPath("1");
        if (status1 == null) {
            return null;
        }
        Data item = status1.getChildByPath("item");
        if (item == null) {
            return null;
        }
        for (Data entry : item.getChildren()) {
            int id = DataTool.getInt("id", entry, 0);
            if (id >= MEDAL_ID_MIN && id < MEDAL_ID_MAX) {
                return id;
            }
        }
        return null;
    }

    /** Write a deterministic medal collection onto the bot. No-op when the table is empty. */
    public static void apply(Character bot) {
        if (bot == null || questMedals.isEmpty()) {
            return;
        }
        List<int[]> eligible = eligibleQuests(bot);
        if (eligible.isEmpty()) {
            return;
        }
        int worn = BotMedal.currentMedalId(bot);
        for (int questId : select(bot.getId(), bot.getLevel(), eligible, worn)) {
            bot.getQuestNAdd(Quest.getInstance(questId)).setStatus(QuestStatus.Status.COMPLETED);
        }
    }

    /** Remove the medal-collection quests this feature may have added (for a re-roll). */
    public static void clear(Character bot) {
        if (bot == null || questMedals.isEmpty()) {
            return;
        }
        Map<Short, QuestStatus> quests = bot.getQuests();
        synchronized (quests) {
            for (Integer questId : questMedals.keySet()) {
                quests.remove((short) (int) questId);
            }
        }
    }

    /**
     * The medal quests this bot may hold: the loaded table filtered by the same legality rule the
     * worn-medal assigner uses. Returns {@code [questId, medalId]} pairs, ascending.
     */
    static List<int[]> eligibleQuests(Character bot) {
        Set<Integer> legalMedals = new TreeSet<>();
        for (BotMedalPool.Medal medal : BotMedalPool.eligibleFor(bot)) {
            legalMedals.add(medal.id);
        }
        if (legalMedals.isEmpty()) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : questMedals.entrySet()) {
            if (legalMedals.contains(e.getValue())) {
                out.add(new int[]{e.getKey(), e.getValue()});
            }
        }
        return out;
    }

    /**
     * Deterministically choose the quest ids to mark completed: a level-scaled count, the worn
     * medal's own quest always present, the rest sampled per cid. Pure, so it is unit-testable.
     */
    static List<Integer> select(int cid, int level, List<int[]> eligible, int wornMedalId) {
        if (eligible.isEmpty() || level < LEVEL_FLOOR) {
            return List.of();
        }
        int k = Math.min(eligible.size(), scaledCount(level, eligible.size()));

        List<int[]> pool = new ArrayList<>(eligible);
        List<Integer> chosen = new ArrayList<>();

        // The worn medal's quest is always in the book when it is eligible.
        if (wornMedalId > 0) {
            for (int[] pair : pool) {
                if (pair[1] == wornMedalId) {
                    chosen.add(pair[0]);
                    break;
                }
            }
        }
        List<int[]> remaining = new ArrayList<>(pool);
        remaining.removeIf(pair -> chosen.contains(pair[0]));
        for (int[] pair : BotDetailRoll.sample(cid, SALT, remaining, k - chosen.size())) {
            chosen.add(pair[0]);
        }
        Collections.sort(chosen);
        return chosen;
    }

    /** 1..max quests, rising with level across the bots' 10..80 band. */
    private static int scaledCount(int level, int max) {
        double t = Math.min(1.0, (double) (level - LEVEL_FLOOR) / (LEVEL_CEIL - LEVEL_FLOOR));
        return 1 + (int) Math.round(t * (max - 1));
    }
}
