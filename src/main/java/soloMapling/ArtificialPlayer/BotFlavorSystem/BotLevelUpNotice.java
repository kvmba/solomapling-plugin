package soloMapling.ArtificialPlayer.BotFlavorSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.util.I18nUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHelpers;

/**
 * The world-wide level-up notice for a bot that levelled silently.
 *
 * <p>Bots advance two ways. When a real player is watching, their kills go through
 * the host's {@code Character.gainExp} and the host already announces the level-up
 * like any player's. When nobody is watching, a bot (and a companion grinding
 * alone) levels by cheap arithmetic — {@code setLevel}/{@code setExp} with no
 * packets — which bypasses {@code gainExp} entirely, so the host never sees it.
 * This closes that gap for the silent paths only.</p>
 *
 * <p>It deliberately mirrors the host's own announcement (same config switch, same
 * i18n key, same notice type) so a bot's notice is indistinguishable from a
 * player's, and only ever delivers to real players — a bot receiving it would be a
 * no-op ({@code BotClient.sendPacket} is empty), so skipping bots just saves the
 * scan of the ~2.5k artificial characters in the world storage.</p>
 */
public final class BotLevelUpNotice {

    private static final Logger log = LoggerFactory.getLogger(BotLevelUpNotice.class);

    private BotLevelUpNotice() {}

    /** Announce {@code bot}'s current level to every real player, if the host switch allows it. */
    public static void announce(Character bot) {
        if (bot == null || bot.getMap() == null || bot.isGM()) {
            return; // matches the host: GM level-ups are not announced
        }
        if (!GameConfig.getServerBoolean("use_announce_global_level_up")) {
            return; // same switch the host uses for real players
        }
        String msg = I18nUtil.getMessage("Character.levelUp.globalNotice",
                bot.getName(), bot.getMap().getMapName(), bot.getLevel());
        for (Character player : bot.getWorldServer().getPlayerStorage().getAllCharacters()) {
            if (BotHelpers.isBot(player)) {
                continue; // real players only; a bot would just drop the packet
            }
            if (player.getCashShop().isOpened()) {
                continue; // matches the host: a popup in the cash shop would spam
            }
            player.dropMessage(6, msg);
        }
        log.info(msg);
    }
}
