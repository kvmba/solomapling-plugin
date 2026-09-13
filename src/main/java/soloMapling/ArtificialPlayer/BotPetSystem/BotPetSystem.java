package soloMapling.ArtificialPlayer.BotPetSystem;

/**
 * Single entry point for the bot-pet feature: load resources, hold the live
 * config, and (un)start the follower. Integration points call {@link #grant},
 * {@link #remove} and {@link #onBotRemoved}; the extension calls
 * {@link #bootstrap} at server ready and {@link #shutdown} at unload.
 */
public final class BotPetSystem {

    /** Run-time kill switch (also honoured by the GM command). */
    private static volatile boolean enabled = true;

    private static volatile BotPetConfig config = BotPetConfig.defaults();

    private BotPetSystem() {
    }

    public static BotPetConfig config() {
        return config;
    }

    public static boolean enabled() {
        return enabled && config.enabled();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void bootstrap() {
        config = BotPetConfig.load();
        BotPetPool.load();
        BotPetNames.load();
        if (!config.enabled()) {
            System.out.println("[BotPetSystem] disabled by config");
            return;
        }
        if (!BotPetPool.isLoaded()) {
            System.out.println("[BotPetSystem] pet pool empty — feature idle");
            return;
        }
        BotPetFollower.start(config);
        System.out.println("[BotPetSystem] ready (pool=" + BotPetPool.size()
                + ", persistCompanions=" + config.persistCompanions() + ")");
    }

    public static void shutdown() {
        BotPetFollower.stop();
    }

    /** Reload the tuning + pools and restart the follower. */
    public static void reload() {
        BotPetFollower.stop();
        BotPetPool.forceReload();
        BotPetNames.forceReload();
        bootstrap();
    }

    /** Give a freshly spawned / loaded bot its pets, if enabled. */
    public static void grant(org.gms.client.Character bot) {
        if (!enabled()) {
            return;
        }
        try {
            BotPetController.grantForBot(bot, config);
        } catch (Throwable t) {
            System.err.println("[BotPetSystem] grant failed for " + botId(bot) + ": " + t);
        }
    }

    /** Remove a bot's pets (used when it becomes an FM shop keeper). */
    public static void remove(org.gms.client.Character bot) {
        try {
            BotPetController.removePets(bot);
        } catch (Throwable t) {
            System.err.println("[BotPetSystem] remove failed for " + botId(bot) + ": " + t);
        }
    }

    /** Drop any per-bot state when the bot leaves the world. */
    public static void onBotRemoved(int botId) {
        BotPetFollower.forget(botId);
    }

    private static int botId(org.gms.client.Character bot) {
        return bot == null ? -1 : bot.getId();
    }
}
