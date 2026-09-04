package soloMapling.Environment;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Player-visible UI strings that live in Java code rather than in a {@code BotDialoguePack} YAML.
 *
 * <p>Bot dialogue (what a bot <em>says</em>) is fully localized through the dialogue packs. This
 * covers the rest: system notices ("X declined your party request"), trade-chat lines, hint-balloon
 * menus, yellow messages, chalkboards - everything a player reads that is not a spoken line.
 *
 * <p>Resolution mirrors {@link LocalizedResources}: {@code BotMessages-<tag>.yaml} first, then the
 * English {@code BotMessages.yaml}. A key absent from the localized copy falls back to English, so
 * an incomplete translation degrades gracefully instead of showing blanks.
 *
 * <p>Placeholders are {@code {0}}, {@code {1}}, … substituted positionally:
 * <pre>{@code
 * BotMessages.get("party.declined", botName)
 * }</pre>
 */
public final class BotMessages {

    private BotMessages() {
    }

    public static final String BASE = "BotMessages";
    public static final String RESOURCE_DIR = "Environment/";

    /**
     * Messages and menu-keyword aliases published as ONE immutable snapshot.
     *
     * <p>A single reference matters: bots read these from many threads (one per bot, plus the
     * chat-message path), and publishing two separate fields let a reader observe a new
     * {@code messages} paired with a stale {@code keywordIndex} - or, worse, catch an
     * {@link #invalidate()} mid-flight and read an empty map, which silently renders the raw key
     * ("party.declined") in front of a player.
     */
    private static final class Pack {
        final Map<String, String> messages;
        final Map<String, List<List<String>>> keywords;

        Pack(Map<String, String> messages, Map<String, List<List<String>>> keywords) {
            this.messages = messages;
            this.keywords = keywords;
        }

        static Pack empty() {
            return new Pack(Map.of(), Map.of());
        }
    }

    private static volatile Pack pack = Pack.empty();

    /**
     * Times the pack has actually been parsed. Test hook: a stampede of first-time readers should
     * increment this exactly once. Never on the steady-state read path.
     */
    private static final java.util.concurrent.atomic.AtomicInteger LOAD_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    static int loadCount() {
        return LOAD_COUNT.get();
    }

    /** Localized message with positional placeholders; the key itself on total miss. */
    public static String get(String key, Object... args) {
        String raw = pack().messages.get(key);
        if (raw == null) {
            return key;
        }
        return args == null || args.length == 0 ? raw : format(raw, args);
    }

    /** Like {@link #get}, but returns {@code fallback} instead of the key when unresolved. */
    public static String getOr(String key, String fallback, Object... args) {
        String raw = pack().messages.get(key);
        if (raw == null) {
            return fallback == null ? key : fallback;
        }
        return args == null || args.length == 0 ? raw : format(raw, args);
    }

    /**
     * Localized labels for a numbered option menu, one key per option in display order.
     *
     * <p>Pass the prefix plus the key suffixes; each is looked up as {@code prefix + "." + suffix}.
     */
    public static List<String> labels(String prefix, String... suffixes) {
        List<String> out = new ArrayList<>(suffixes.length);
        for (String suffix : suffixes) {
            out.add(get(prefix + "." + suffix));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Keyword lists for a numbered option menu: one list per option, in the same order as
     * {@link #labels}. Each option always matches its number, and additionally matches any of
     * its keywords.
     *
     * <p>The returned lists <em>merge</em> three sources, in order:
     * <ol>
     *   <li>{@code englishKeywords} - hand-written per option. These lead because several menus
     *       depend on their exact words and ordering (TrainingBot keeps its flavor-chat keywords
     *       deliberately narrow so "how about joining my party" cannot hijack the party roll).</li>
     *   <li>{@code <prefix>.<suffix>.keywords} from the message YAML - per-language aliases.</li>
     *   <li>the localized label text, split on punctuation - but only for non-Latin scripts (see
     *       {@link #splitKeywords}). Latin labels are skipped: splitting "How's the training?"
     *       yields "how", which is exactly the greedy keyword that would hijack the party roll.</li>
     * </ol>
     *
     * <p>Merging rather than overriding is deliberate: a player may answer in either language
     * regardless of which language the menu is displayed in.
     */
    public static List<List<String>> keywords(String prefix, String[] suffixes, String[][] englishKeywords) {
        Pack current = pack();
        List<List<String>> out = new ArrayList<>(suffixes.length);
        for (int i = 0; i < suffixes.length; i++) {
            Set<String> perOption = new LinkedHashSet<>();
            if (englishKeywords != null && i < englishKeywords.length && englishKeywords[i] != null) {
                Collections.addAll(perOption, englishKeywords[i]);
            }
            String key = prefix + "." + suffixes[i];
            List<List<String>> extras = current.keywords.get(key + ".keywords");
            if (extras != null) {
                for (List<String> aliasGroup : extras) {
                    perOption.addAll(aliasGroup);
                }
            }
            // The localized label itself, minus punctuation: "跟我来！" -> 跟我来. Chinese labels
            // are one unbroken run of characters, so this yields exactly the text on screen.
            String label = current.messages.get(key);
            if (label != null && !label.isBlank()) {
                perOption.addAll(splitKeywords(label));
            }
            out.add(List.copyOf(perOption));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Splits a label into typeable keywords on any run of non-letter/non-digit characters.
     *
     * <p><b>Latin text is intentionally excluded.</b> Splitting "How's the training?" yields
     * "how"/"the"/"training", and "how" is exactly the greedy keyword the TrainingBot comment
     * warns about - it lets "how about joining my party" hijack the flavor-chat option instead of
     * rolling a party invite. English menus already carry hand-written keyword lists, so deriving
     * more from their labels only widens them.
     *
     * <p>Non-Latin labels are derived, because a CJK label is one unbroken run that would
     * otherwise need a hand-written alias for every menu in every language. Single characters are
     * kept there ("是" is a complete word); the Latin path never sees them.
     */
    private static List<String> splitKeywords(String label) {
        List<String> out = new ArrayList<>();
        for (String part : label.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (part.isEmpty() || part.matches("\\p{ASCII}+")) {
                continue;
            }
            out.add(part);
        }
        return out;
    }

    /**
     * Drop cached messages (after a language switch or a hot-edit of a message pack).
     *
     * <p>Synchronized so that a stampede of threads finding an empty pack does not each parse the
     * YAML. Note the correctness guarantee for readers comes from {@code pack} being a SINGLE
     * volatile reference, not from this lock - swapping one reference is atomic, so a reader sees
     * either the old complete snapshot or the new one, never a mix and never an empty map.
     */
    public static void invalidate() {
        synchronized (BotMessages.class) {
            pack = Pack.empty();
        }
    }

    private static String format(String raw, Object[] args) {
        String out = raw;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }

    /**
     * Current snapshot, loading it on first use.
     *
     * <p>Steady state is a single volatile read - no lock. Parsing runs outside the lock (it opens
     * two resources and parses YAML, which would otherwise stall every bot thread behind it), so a
     * losing race simply rebuilds an equivalent snapshot; only the final reference publication is
     * synchronized, and that is what makes the swap atomic for readers.
     */
    private static Pack pack() {
        Pack current = pack;
        if (!current.messages.isEmpty()) {
            return current;
        }
        synchronized (BotMessages.class) {
            if (!pack.messages.isEmpty()) {
                return pack;
            }
            Map<String, String> built = new HashMap<>();
            Map<String, List<List<String>>> builtKeywords = new HashMap<>();
            // English first so the localized copy can override it.
            loadInto(englishPath(), built, builtKeywords);
            String localized = localizedPath();
            if (!localized.equals(englishPath())) {
                loadInto(localized, built, builtKeywords);
            }
            pack = new Pack(Map.copyOf(built), Map.copyOf(builtKeywords));
            LOAD_COUNT.incrementAndGet();
            return pack;
        }
    }

    // NOTE: unlike BotDialoguePack (a DIRECTORY with many per-bot files inside), the message pack
    // is a single file per language - BotMessages.yaml / BotMessages-zh-CN.yaml - so the localized
    // path comes straight from LocalizedResources.directoryName() rather than from
    // LocalizedResources.resolve(), which assumes the directory-plus-filename shape.
    private static String englishPath() {
        return RESOURCE_DIR + BASE + ".yaml";
    }

    private static String localizedPath() {
        return RESOURCE_DIR + LocalizedResources.directoryName(BASE) + ".yaml";
    }

    private static void loadInto(String resourcePath, Map<String, String> out,
                                 Map<String, List<List<String>>> keywordsOut) {
        Map<String, Object> root;
        try (Reader reader = PluginResources.openReader(resourcePath)) {
            YamlReader yaml = new YamlReader(reader);
            Object parsed = yaml.read();
            if (!(parsed instanceof Map)) {
                return;
            }
            root = castMap(parsed);
        } catch (IOException | RuntimeException e) {
            // Not fatal: an unreadable localized copy degrades to English. Worth printing, though -
            // a silently-English server otherwise looks like a translation that was never written.
            System.err.println("[BotMessages] failed to load " + resourcePath + ": " + e.getMessage());
            return;
        }
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List) {
                keywordsOut.put(entry.getKey(), toStringLists(value));
            } else if (value != null) {
                out.put(entry.getKey(), String.valueOf(value));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object parsed) {
        return (Map<String, Object>) parsed;
    }

    private static List<List<String>> toStringLists(Object raw) {
        List<List<String>> outer = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item instanceof List) {
                List<String> inner = new ArrayList<>();
                for (Object value : (List<?>) item) {
                    if (value != null) {
                        inner.add(String.valueOf(value).toLowerCase());
                    }
                }
                outer.add(inner);
            } else if (item != null) {
                outer.add(List.of(String.valueOf(item).toLowerCase()));
            }
        }
        return outer;
    }

}
