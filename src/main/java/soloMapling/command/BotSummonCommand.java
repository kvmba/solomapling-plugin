package soloMapling.command;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonSystem;

/*
 * !botsummon — bot summon feature control / diagnostics.
 *
 *   !botsummon status          config + live switches
 *   !botsummon enable|disable  runtime kill switch
 *   !botsummon reload          re-read BotSummon config and restart the follower
 *   !botsummon grant <botId>   force-grant a summon to a bot
 *   !botsummon clear <botId>   strip a bot's summon (debug)
 */
public class BotSummonCommand extends Command {
    {
        setDescription("Bot summon feature control (status/enable/disable/reload/grant/clear).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0 || params[0].equalsIgnoreCase("help")) {
            help(player);
            return;
        }

        switch (params[0].toLowerCase()) {
            case "status" -> player.dropMessage("botsummon: enabled=" + BotSummonSystem.enabled()
                    + " config.enabled=" + BotSummonSystem.config().enabled()
                    + " spawnChance=" + BotSummonSystem.config().spawnChance()
                    + " minLevel=" + BotSummonSystem.config().minLevel()
                    + " tick=" + BotSummonSystem.config().followTickMs() + "ms");
            case "enable" -> {
                BotSummonSystem.setEnabled(true);
                player.dropMessage("botsummon enabled (new bots may carry a summon)");
            }
            case "disable" -> {
                BotSummonSystem.setEnabled(false);
                player.dropMessage("botsummon disabled (existing summons remain until removed)");
            }
            case "reload" -> {
                BotSummonSystem.reload();
                player.dropMessage("botsummon reloaded");
            }
            case "grant" -> {
                Character bot = resolveTarget(player, params);
                if (bot == null) {
                    return;
                }
                BotSummonSystem.grant(bot);
                player.dropMessage("granted a summon to " + bot.getName()
                        + " (job=" + bot.getJob().name() + ")");
            }
            case "clear" -> {
                Character bot = resolveTarget(player, params);
                if (bot == null) {
                    return;
                }
                BotSummonSystem.remove(bot);
                player.dropMessage("botsummon clear: " + bot.getName() + "'s summon removed");
            }
            default -> help(player);
        }
    }

    private Character resolveTarget(Character player, String[] params) {
        if (params.length >= 2) {
            try {
                int id = Integer.parseInt(params[1].trim());
                Character bot = soloMapling.server.SoloMaplingUtilities.getChr(id);
                if (bot == null) {
                    player.dropMessage("botsummon: no bot online with id " + id);
                }
                return bot;
            } catch (NumberFormatException e) {
                player.dropMessage("botsummon: botId must be a number");
                return null;
            }
        }
        player.dropMessage("botsummon: pass a botId (this build has no target selection)");
        return null;
    }

    private void help(Character player) {
        player.dropMessage("Usage: !botsummon status|enable|disable|reload|grant <botId>|clear <botId>");
    }
}
