package soloMapling.Environment;

/**
 * Language-aware lookup for packaged resource directories that ship a localized sibling
 * ({@code BotDialoguePack} / {@code BotDialoguePack-zh-CN}, {@code FMNameDesc} /
 * {@code FMNameDesc-zh-CN}, …), matching BeiDou's {@code scripts} / {@code scripts-zh-CN}
 * pattern: localized copy first, then the English default.
 */
public final class LocalizedResources {

    private LocalizedResources() {
    }

    /** {@code base} for English, {@code base-<tag>} otherwise. */
    public static String directoryName(String base) {
        if (SoloMaplingLanguageConfig.isDefaultEnglish()) {
            return base;
        }
        return base + "-" + SoloMaplingLanguageConfig.languageTag();
    }

    /**
     * Resolves {@code <parent><base>[-<tag>]/<fileName>} to a path {@link PluginResources} can
     * open, falling back to the English directory. Returns null when neither holds the file.
     */
    public static String resolve(String parent, String base, String fileName) {
        String localizedDir = directoryName(base);

        String localized = parent + localizedDir + "/" + fileName;
        if (PluginResources.exists(localized)) {
            return localized;
        }

        if (!base.equals(localizedDir)) {
            String def = parent + base + "/" + fileName;
            if (PluginResources.exists(def)) {
                return def;
            }
        }

        return null;
    }
}
