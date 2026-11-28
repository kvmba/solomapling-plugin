package soloMapling.ArtificialPlayer.BotFlavorSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.gms.client.Character;
import org.gms.util.I18nUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.Environment.PluginResources;

import java.io.IOException;
import java.util.Map;

/**
 * World-wide notice for simulated training bot level-ups that bypass
 * {@link Character#gainExp(int)}.
 */
public final class BotLevelUpNotice {

    private static final Logger log = LoggerFactory.getLogger(BotLevelUpNotice.class);
    private static final String RESOURCE_PATH = "ArtificialPlayer/BotFlavorSystem/BotLevelUpNotice.yaml";
    private static volatile boolean enabled;

    private BotLevelUpNotice() {}

    public static void loadConfig() {
        boolean configured;
        try (var reader = PluginResources.openReader(RESOURCE_PATH)) {
            configured = new YamlReader(reader).read() instanceof Map<?, ?> config
                    && Boolean.parseBoolean(String.valueOf(config.get("enabled")));
        } catch (IOException e) {
            log.warn("Could not load {}; bot level-up notices disabled", RESOURCE_PATH, e);
            configured = false;
        }
        enabled = configured;
    }

    public static void announce(Character bot) {
        if (!enabled || bot == null || bot.getMap() == null || bot.isGM()) {
            return;
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
        log.debug(msg);
    }
}
