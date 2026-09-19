package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.gms.client.Character;
import org.gms.client.MonsterBook;
import org.gms.provider.Data;
import org.gms.provider.wz.WZFiles;
import org.gms.provider.wz.XMLWZData;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Presets the monster-book (怪物图鉴) aggregates a bot shows in its character-info window.
 *
 * <p>The detail window ({@code PacketCreator.charInfo}) reads exactly four aggregate values -
 * {@code bookLevel / normalCard / specialCard / totalCards} - plus the cover mob id. It never
 * reads the per-card map, and the only packet that serialises the map ({@code addMonsterBookInfo})
 * is sent solely to a real player on login, so a bot's card map is never on the wire. We therefore
 * set only the three private counters via reflection (they are plain non-final ints with no setter;
 * {@code addCard} is unusable because it broadcasts and dereferences a null headless player).
 *
 * <p>Values are derived from the character id (stable per companion across restarts) and the bot's
 * level, fed through the host's own book-level curve so the shown level matches the card count
 * exactly as the engine would compute it.
 */
public final class BotMonsterBook {

    /** Card prefix {@code 0238xxxx}; special cards are {@code id/1000 >= 2388} (host convention). */
    private static final int CARD_ID_MIN = 2380000;
    private static final int CARD_ID_MAX = 2389000;
    private static final int SPECIAL_BOUNDARY = 2388000;

    /** Per-dataset salt, so this feature's rolls are independent of every other cid-derived roll. */
    private static final int SALT = 0x4D42_0001;

    /** Normal-card count span and the bots' level band (level 10..80). */
    private static final int NORMAL_MIN = 4;
    private static final int NORMAL_MAX = 40;
    private static final int SPECIAL_MIN = 0;
    private static final int SPECIAL_MAX = 6;
    private static final int LEVEL_FLOOR = 10;
    private static final int LEVEL_CEIL = 80;

    private static volatile List<Integer> normalPool = List.of();
    private static volatile List<Integer> specialPool = List.of();
    private static volatile boolean loaded = false;

    private BotMonsterBook() {
    }

    /** Scan the client WZ once for the real, localised card ids. Never throws. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        long start = System.currentTimeMillis();
        List<Integer> normal = new ArrayList<>();
        List<Integer> special = new ArrayList<>();
        try {
            Path file = WZFiles.ITEM.getFile().resolve("Consume/0238.img.xml");
            if (!Files.isRegularFile(file)) {
                System.err.println("[BotMonsterBook] card file not found at " + file);
                return;
            }
            try (FileInputStream fis = new FileInputStream(file.toFile())) {
                Data root = XMLWZData.parse(fis);
                for (Data child : root.getChildren()) {
                    int id;
                    try {
                        id = Integer.parseInt(child.getName());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (id < CARD_ID_MIN || id >= CARD_ID_MAX || !BotHelpers.isUsableItem(id)) {
                        continue;
                    }
                    (id >= SPECIAL_BOUNDARY ? special : normal).add(id);
                }
            }
        } catch (Exception e) {
            System.err.println("[BotMonsterBook] failed to scan cards: " + e);
        } finally {
            normalPool = normal;
            specialPool = special;
            loaded = true;
        }
        System.out.println("[BotMonsterBook] Loaded " + normalPool.size() + " normal + "
                + specialPool.size() + " special cards in " + (System.currentTimeMillis() - start) + "ms");
    }

    /** Write a deterministic monster-book aggregate onto the bot. No-op when pools are empty. */
    public static void apply(Character bot) {
        if (bot == null || bot.getMap() == null || normalPool.isEmpty()) {
            return;
        }
        int normal = Math.min(pickCount(bot.getId(), bot.getLevel(), NORMAL_MIN, NORMAL_MAX, 0),
                normalPool.size());
        int special = Math.min(pickCount(bot.getId(), bot.getLevel(), SPECIAL_MIN, SPECIAL_MAX, 1),
                specialPool.size());

        MonsterBook book = bot.getMonsterBook();
        if (book == null) {
            return;
        }
        if (writeCounters(book, normal, special, bookLevelFor(normal + special))) {
            // Cover stays 0: getCardMobId(cover) unboxes, and an unbacked cover id would NPE the
            // detail packet for every viewer. The bot never sets one.
            bot.setBookCover(0);
        }
    }

    /** Reset the aggregates (used before a re-roll). */
    public static void clear(Character bot) {
        if (bot == null) {
            return;
        }
        MonsterBook book = bot.getMonsterBook();
        if (book != null) {
            writeCounters(book, 0, 0, 1);
        }
        bot.setBookCover(0);
    }

    /**
     * Card count for one side of the book: 0 below {@link #LEVEL_FLOOR}, rising linearly to
     * {@code max} at {@link #LEVEL_CEIL}, with a small per-cid jitter so bots at the same level
     * do not all match. Pure, so it is unit-testable without WZ.
     */
    static int pickCount(int cid, int level, int min, int max, int jitterSalt) {
        if (level < LEVEL_FLOOR || max <= 0) {
            return 0;
        }
        double t = Math.min(1.0, (double) (level - LEVEL_FLOOR) / (LEVEL_CEIL - LEVEL_FLOOR));
        int span = max - min;
        int base = min + (int) Math.round(t * span);
        int jitter = span > 0 ? Math.floorMod(BotDetailRoll.mix(cid, SALT + jitterSalt), 3) - 1 : 0;
        return Math.max(0, Math.min(max, base + jitter));
    }

    /**
     * The book level the host would compute for {@code totalCards}, replayed from
     * {@code MonsterBook.calculateLevel()}: {@code level=0, exp=1; do { level++; exp += level*10; }
     * while (totalCards >= exp)}. Pure.
     */
    static int bookLevelFor(int totalCards) {
        int level = 0;
        int expToNextLevel = 1;
        do {
            level++;
            expToNextLevel += level * 10;
        } while (totalCards >= expToNextLevel);
        return level;
    }

    // ── reflection onto the private counters ────────────────────────────────

    private static final Field NORMAL_CARD = field("normalCard");
    private static final Field SPECIAL_CARD = field("specialCard");
    private static final Field BOOK_LEVEL = field("bookLevel");

    private static Field field(String name) {
        try {
            Field f = MonsterBook.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            System.err.println("[BotMonsterBook] cannot access MonsterBook." + name + ": " + e);
            return null;
        }
    }

    private static boolean writeCounters(MonsterBook book, int normal, int special, int level) {
        if (NORMAL_CARD == null || SPECIAL_CARD == null || BOOK_LEVEL == null) {
            return false;
        }
        try {
            NORMAL_CARD.setInt(book, normal);
            SPECIAL_CARD.setInt(book, special);
            BOOK_LEVEL.setInt(book, level);
            return true;
        } catch (ReflectiveOperationException e) {
            System.err.println("[BotMonsterBook] failed to write counters: " + e);
            return false;
        }
    }
}
