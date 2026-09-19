package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.gms.client.Character;

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
        if (!ENABLED || bot == null) {
            return;
        }
        BotMonsterBook.apply(bot);
        BotMedalBook.apply(bot);
        BotWishList.apply(bot);
    }

    /** Re-roll after a level/job override, so the shown data matches the bot's final state. */
    public static void reroll(Character bot) {
        if (!ENABLED || bot == null) {
            return;
        }
        BotMonsterBook.clear(bot);
        BotMedalBook.clear(bot);
        BotWishList.clear(bot);
        apply(bot);
    }
}
