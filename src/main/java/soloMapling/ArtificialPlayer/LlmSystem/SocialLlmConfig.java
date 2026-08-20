package soloMapling.ArtificialPlayer.LlmSystem;

import org.gms.extension.api.HostConfig;

/**
 * Host-configurable knobs for SocialBot LLM chat ({@code solomapling.llm.*}).
 */
public final class SocialLlmConfig {

    private SocialLlmConfig() {
    }

    public static final String PREFIX = "solomapling.llm.";
    public static final String KEY_ENABLED = PREFIX + "enabled";
    public static final String KEY_API_KEY = PREFIX + "api-key";
    public static final String KEY_MODEL = PREFIX + "model";
    public static final String KEY_MAX_TOKENS = PREFIX + "max-tokens";
    public static final String KEY_TIMEOUT_MS = PREFIX + "timeout-ms";
    public static final String KEY_HISTORY_TURNS = PREFIX + "history-turns";
    public static final String KEY_FALLBACK_YAML = PREFIX + "fallback-to-yaml";

    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    public static final int DEFAULT_MAX_TOKENS = 80;
    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_HISTORY_TURNS = 8;
    public static final int MAX_REPLY_CHARS = 120;

    private static volatile boolean enabled;
    private static volatile String apiKey = "";
    private static volatile String model = DEFAULT_MODEL;
    private static volatile int maxTokens = DEFAULT_MAX_TOKENS;
    private static volatile int timeoutMs = DEFAULT_TIMEOUT_MS;
    private static volatile int historyTurns = DEFAULT_HISTORY_TURNS;
    private static volatile boolean fallbackToYaml = true;

    public static void configure(HostConfig config) {
        if (config == null) {
            resetDefaults();
            return;
        }
        enabled = config.getBool(KEY_ENABLED, false);
        apiKey = firstNonBlank(
                config.getString(KEY_API_KEY, ""),
                System.getenv("DEEPSEEK_API_KEY"));
        model = config.getString(KEY_MODEL, DEFAULT_MODEL);
        maxTokens = clamp(config.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS), 16, 512);
        timeoutMs = clamp(config.getInt(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS), 2_000, 60_000);
        historyTurns = clamp(config.getInt(KEY_HISTORY_TURNS, DEFAULT_HISTORY_TURNS), 0, 20);
        fallbackToYaml = config.getBool(KEY_FALLBACK_YAML, true);
    }

    private static void resetDefaults() {
        enabled = false;
        apiKey = firstNonBlank("", System.getenv("DEEPSEEK_API_KEY"));
        model = DEFAULT_MODEL;
        maxTokens = DEFAULT_MAX_TOKENS;
        timeoutMs = DEFAULT_TIMEOUT_MS;
        historyTurns = DEFAULT_HISTORY_TURNS;
        fallbackToYaml = true;
    }

    public static boolean enabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public static String apiKey() {
        return apiKey;
    }

    public static String model() {
        return model;
    }

    public static int maxTokens() {
        return maxTokens;
    }

    public static int timeoutMs() {
        return timeoutMs;
    }

    public static int historyTurns() {
        return historyTurns;
    }

    public static boolean fallbackToYaml() {
        return fallbackToYaml;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return "";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
