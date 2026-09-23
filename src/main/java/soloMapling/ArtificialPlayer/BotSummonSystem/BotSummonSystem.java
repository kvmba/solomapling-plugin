package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;

/**
 * Single entry point for the bot-summon feature: load the config, hold the live tuning, and
 * (un)start the follower. Integration points call {@link #grant}, {@link #remove} and
 * {@link #forget}; the extension calls {@link #bootstrap} at server ready and {@link #shutdown}
 * at unload.
 */
public final class BotSummonSystem {

    /** Run-time kill switch (also honoured by the GM command). */
    private static volatile boolean enabled = true;

    private static volatile BotSummonConfig config = BotSummonConfig.defaults();

    private BotSummonSystem() {}

    public static BotSummonConfig config() {
        return config;
    }

    public static boolean enabled() {
        return enabled && config.enabled();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void bootstrap() {
        config = BotSummonConfig.load();
        if (!config.enabled()) {
            System.out.println("[BotSummonSystem] disabled by config");
            return;
        }
        BotSummonFollower.start(config);
        System.out.println("[BotSummonSystem] ready (spawnChance=" + config.spawnChance()
                + ", minLevel=" + config.minLevel() + ")");
    }

    public static void shutdown() {
        BotSummonFollower.stop();
    }

    /** Reload the tuning and restart the follower. */
    public static void reload() {
        BotSummonFollower.stop();
        bootstrap();
    }

    /** Give a freshly spawned / loaded bot its summon, if enabled. */
    public static void grant(Character bot) {
        if (!enabled()) {
            return;
        }
        try {
            BotSummonController.grantForBot(bot, config);
        } catch (Throwable t) {
            System.err.println("[BotSummonSystem] grant failed for " + botId(bot) + ": " + t);
        }
    }

    /** Remove a bot's summons (used when it becomes an FM shop keeper or leaves the world). */
    public static void remove(Character bot) {
        try {
            BotSummonController.removeSummons(bot);
        } catch (Throwable t) {
            System.err.println("[BotSummonSystem] remove failed for " + botId(bot) + ": " + t);
        }
    }

    /** Drop follower state for a bot that is already gone (despawn path). */
    public static void forget(int botId) {
        BotSummonFollower.forget(botId);
    }

    private static int botId(Character bot) {
        return bot == null ? -1 : bot.getId();
    }
}
