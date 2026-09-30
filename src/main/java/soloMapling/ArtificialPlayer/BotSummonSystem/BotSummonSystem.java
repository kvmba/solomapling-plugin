package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;

/**
 * Single entry point for the bot-summon feature: load the config, hold the live tuning, and
 * (un)start the follower. Integration points call {@link #grant} and {@link #remove}; the extension
 * calls {@link #bootstrap} at server ready and {@link #shutdown} at unload.
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

    /**
     * GM 技能观测表演：为一个 bot 强制召唤指定的召唤兽（无视 spawnChance/minLevel
     * 等概率与门槛），技能等级按 bot 的角色等级补授（与 grant 的 teach 路径一致）。
     * 返回 false 表示该技能不是已注册召唤兽、无法解析或生成失败。
     */
    public static boolean showSummon(Character bot, int skillId) {
        if (bot == null || bot.getMap() == null || BotSummonTable.forSkill(skillId) == null) {
            return false;
        }
        try {
            BotSummonController.spawnForShow(bot, skillId);
            return BotSummonFollower.isTracked(bot.getId());
        } catch (Throwable t) {
            System.err.println("[BotSummonSystem] showSummon failed for " + botId(bot)
                    + " skill " + skillId + ": " + t);
            return false;
        }
    }

    private static int botId(Character bot) {
        return bot == null ? -1 : bot.getId();
    }
}
