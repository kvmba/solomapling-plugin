package soloMapling.Environment;

import org.gms.extension.api.HostConfig;

/**
 * SoloMapling dialogue / script language. Mirrors the host {@code gms.service.language} convention
 * ({@code en-US}, {@code zh-CN}, …) and selects {@code BotDialoguePack} vs {@code BotDialoguePack-zh-CN}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code solomapling.language} from host config ({@code application.yml})</li>
 *   <li>{@code gms.service.language} (BeiDou server language)</li>
 *   <li>{@code en-US}</li>
 * </ol>
 */
public final class SoloMaplingLanguageConfig {

    private SoloMaplingLanguageConfig() {
    }

    public static final String KEY = "solomapling.language";
    public static final String HOST_KEY = "gms.service.language";
    public static final String DEFAULT = "en-US";

    private static volatile String languageTag = DEFAULT;

    /** Called once from {@link soloMapling.plugin.SoloMaplingExtension#onLoad}. */
    public static void configure(HostConfig config) {
        if (config == null) {
            languageTag = DEFAULT;
            return;
        }
        String lang = config.getString(KEY, "");
        if (lang == null || lang.isBlank()) {
            lang = config.getString(HOST_KEY, DEFAULT);
        }
        languageTag = normalize(lang);
    }

    /** For tests and live reload via {@code !env language reload}. */
    public static void setLanguageTag(String tag) {
        languageTag = normalize(tag);
    }

    public static String languageTag() {
        return languageTag;
    }

    /** {@code BotDialoguePack} for English; {@code BotDialoguePack-zh-CN} for localized packs. */
    public static String dialoguePackDirectoryName() {
        if (isDefaultEnglish(languageTag)) {
            return "BotDialoguePack";
        }
        return "BotDialoguePack-" + languageTag;
    }

    public static boolean isDefaultEnglish() {
        return isDefaultEnglish(languageTag);
    }

    static boolean isDefaultEnglish(String tag) {
        if (tag == null || tag.isBlank()) {
            return true;
        }
        return "en".equalsIgnoreCase(tag) || "en-us".equalsIgnoreCase(tag);
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        return raw.trim();
    }
}
