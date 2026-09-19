package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.gms.client.Character;
import org.gms.client.MonsterBook;
import org.gms.client.QuestStatus;
import soloMapling.ArtificialPlayer.BotMedalSystem.BotMedal;

import java.util.ArrayList;
import java.util.List;

/**
 * Presets the data a bot shows in its character-info window (CUIUserInfo) - the same window a
 * player opens on another character. For a bot this packet ({@code PacketCreator.charInfo}) used
 * to carry whatever the {@code fmbot} template character held (empty or identical for everyone);
 * this fills it in with plausible, per-bot values at spawn.
 *
 * <p>Everything here is pure in-memory {@link Character} state, so the window is populated the
 * moment the bot exists - no packet code, no host change, no new event. Injected at the single
 * decoration finishing point ({@code BotDecorate.setBotVariables}) and at persistent-companion load
 * ({@code BotGeneration.loadPersistentBot}), exactly like {@code BotMedal}.
 *
 * <p>Deterministic per character id, so a companion shows the same content across restarts and a
 * re-roll is idempotent. Called only on the decoration/load path (never per tick).
 */
public final class BotDetailWindow {

    /** Global kill-switch, mirroring {@code BotMedal.ENABLED}. */
    public static boolean ENABLED = true;

    private BotDetailWindow() {
    }

    /** Populate the bot's detail-window data. Call after level/job/fame/medal are final. */
    public static void apply(Character bot) {
        if (!ENABLED || bot == null || bot.getMap() == null) {
            return;
        }
        BotMonsterBook.apply(bot);
        BotMedalBook.apply(bot);
        BotWishList.apply(bot);
    }

    /** Re-roll after a level/job override, so the shown data matches the bot's final state. */
    public static void reroll(Character bot) {
        if (!ENABLED || bot == null || bot.getMap() == null) {
            return;
        }
        BotMonsterBook.clear(bot);
        BotMedalBook.clear(bot);
        BotWishList.clear(bot);
        apply(bot);
    }

    /**
     * A one-line summary of what the bot's detail window currently holds, read back from the live
     * engine state (not the preset inputs) - the GM "逐个复查" readout.
     */
    public static String describe(Character bot) {
        if (bot == null) {
            return "detail: <no bot>";
        }
        MonsterBook book = bot.getMonsterBook();
        String bookPart = book == null ? "n/a"
                : "lv" + book.getBookLevel() + " normal=" + book.getNormalCard()
                  + " special=" + book.getSpecialCard() + " total=" + book.getTotalCards()
                  + " cover=" + bot.getMonsterBookCover();

        List<Integer> medalQuests = new ArrayList<>();
        for (QuestStatus qs : bot.getCompletedQuests()) {
            int id = qs.getQuest().getId();
            if (id >= 29000) {
                medalQuests.add(id);
            }
        }
        String medalPart = "worn=" + BotMedal.currentMedalId(bot)
                + " collected=" + medalQuests.size() + medalQuests;

        String wishPart = bot.getCashShop() == null ? "n/a"
                : bot.getCashShop().getWishList().size() + " " + bot.getCashShop().getWishList();

        return "monsterbook[" + bookPart + "] medals[" + medalPart + "] wishlist[" + wishPart + "]";
    }
}
