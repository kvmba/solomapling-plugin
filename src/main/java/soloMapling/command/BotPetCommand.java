package soloMapling.command;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import soloMapling.ArtificialPlayer.BotPetSystem.BotPetSystem;

/*
 * !botpet — bot pet feature control / diagnostics.
 *
 *   !botpet status            config + live switches
 *   !botpet enable|disable    runtime kill switch
 *   !botpet reload            re-read BotPet config/pools and restart the follower
 *   !botpet grant <botId>     force-grant pets to a bot
 *   !botpet clear <botId>     strip a bot's pets (debug)
 */
public class BotPetCommand extends Command {
    {
        setDescription("Bot pet feature control (status/enable/disable/reload/grant/clear).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0 || params[0].equalsIgnoreCase("help")) {
            help(player);
            return;
        }

        switch (params[0].toLowerCase()) {
            case "status" -> {
                player.dropMessage("botpet: enabled=" + BotPetSystem.enabled()
                        + " config.enabled=" + BotPetSystem.config().enabled()
                        + " pool=" + soloMapling.ArtificialPlayer.BotPetSystem.BotPetPool.size()
                        + " persistCompanions=" + BotPetSystem.config().persistCompanions()
                        + " tick=" + BotPetSystem.config().followTickMs() + "ms");
            }
            case "enable" -> {
                BotPetSystem.setEnabled(true);
                player.dropMessage("botpet enabled (new bots will carry pets)");
            }
            case "disable" -> {
                BotPetSystem.setEnabled(false);
                player.dropMessage("botpet disabled (existing pets remain until removed)");
            }
            case "reload" -> {
                BotPetSystem.reload();
                player.dropMessage("botpet reloaded");
            }
            case "grant" -> {
                Character bot = resolveTarget(player, params);
                if (bot == null) {
                    return;
                }
                BotPetSystem.grant(bot);
                player.dropMessage("granted pets to " + bot.getName() + " (pets=" + bot.getNoPets() + ")");
            }
            case "clear" -> {
                Character bot = resolveTarget(player, params);
                if (bot == null) {
                    return;
                }
                BotPetSystem.remove(bot);
                player.dropMessage("botpet clear: " + bot.getName()
                        + " now has " + bot.getNoPets() + " pet(s)"
                        + " (companions keep their saved pets)");
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
                    player.dropMessage("botpet: no bot online with id " + id);
                }
                return bot;
            } catch (NumberFormatException e) {
                player.dropMessage("botpet: botId must be a number");
                return null;
            }
        }
        player.dropMessage("botpet: pass a botId (this build has no target selection)");
        return null;
    }

    private void help(Character player) {
        player.dropMessage("Usage: !botpet status|enable|disable|reload|grant <botId>|clear <botId>");
    }
}
