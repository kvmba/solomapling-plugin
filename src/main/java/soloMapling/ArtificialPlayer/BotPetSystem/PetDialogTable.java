package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.WZFiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The spoken lines a pet can say, read from {@code String.wz/PetDialog.img/<id>}.
 *
 * <p>Each pet's {@code Item.wz/Pet/<id>.img/interact} menu names its commands {@code c1},
 * {@code c2}, … and each command's {@code success}/{@code fail} branch lists dialogue KEYS
 * ({@code c1_s1}, {@code c1_f2}, …). The actual text lives here, in the pet's own
 * {@code PetDialog} node, keyed by those names — and it is LOCALIZED (the zh-CN data reads
 * Chinese, e.g. {@code c1_s1 = "喵~"}). The client never receives the text in a command
 * packet, so a bot that only sends {@code commandResponse} animates without ever speaking;
 * this table supplies the line for the plugin to send explicitly via {@code PET_CHAT}.</p>
 *
 * <p>Lazy and best-effort: a pet with no readable WZ yields an empty map and the caller
 * simply says nothing, so a missing/short WZ can never break a bot tick.</p>
 */
public final class PetDialogTable {

    private static final Map<Integer, Map<String, String>> CACHE = new ConcurrentHashMap<>();
    private static volatile DataProvider stringProvider;

    private PetDialogTable() {
    }

    /**
     * Pick a line for the pet's {@code commandIndex} command (the client's {@code interact/<N>}
     * index, whose dialogue keys are prefixed {@code c<N+1>}). {@code obeyed} selects the
     * {@code _s} (success) or {@code _f} (fail) corpus. Returns {@code null} when the pet has
     * no readable dialogue for that command. Only keys that ACTUALLY exist are considered, so a
     * stray key in the pet's interact data (some pets reference lines their dialog omits) can
     * never yield a blank line.
     */
    static String lineFor(int petItemId, int commandIndex, boolean obeyed, Random rng) {
        return pickLine(linesFor(petItemId), commandIndex, obeyed, rng);
    }

    /**
     * Pure key selection over a pet's {@code PetDialog} map — the testable heart of
     * {@link #lineFor}: gather the values whose key is {@code c<commandIndex+1>_<s|f><digits>}
     * and choose one uniformly. Split out (like {@code PetCommandInterpreter}) so the rule is
     * covered without loading WZ.
     */
    static String pickLine(Map<String, String> lines, int commandIndex, boolean obeyed, Random rng) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        String prefix = "c" + (commandIndex + 1) + (obeyed ? "_s" : "_f");
        List<String> matches = lines.entrySet().stream()
                .filter(e -> isDigitSuffix(e.getKey(), prefix))
                .map(Map.Entry::getValue)
                .filter(v -> v != null && !v.isBlank())
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(rng.nextInt(matches.size()));
    }

    /** Key is {@code prefix} followed by one or more ASCII digits (so {@code c1_s} never matches
     *  {@code c11_s1}, and a bare {@code c1_s} with no index is not a line). */
    private static boolean isDigitSuffix(String key, String prefix) {
        if (key == null || !key.startsWith(prefix)) {
            return false;
        }
        String rest = key.substring(prefix.length());
        if (rest.isEmpty()) {
            return false;
        }
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) < '0' || rest.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** All localized {@code name -> text} lines for this pet, in a stable order. */
    static Map<String, String> linesFor(int petItemId) {
        Map<String, String> cached = CACHE.get(petItemId);
        if (cached != null) {
            return cached;
        }
        Map<String, String> loaded = loadLines(petItemId);
        CACHE.put(petItemId, loaded);
        return loaded;
    }

    private static Map<String, String> loadLines(int petItemId) {
        try {
            DataProvider provider = stringProvider();
            if (provider == null) {
                return Map.of();
            }
            Data root = provider.getData("PetDialog.img");
            if (root == null) {
                return Map.of();
            }
            Data petNode = root.getChildByPath(Integer.toString(petItemId));
            if (petNode == null) {
                return Map.of();
            }
            Map<String, String> out = new HashMap<>();
            for (Data child : petNode.getChildren()) {
                Object value = child.getData();
                if (value instanceof String s) {
                    out.put(child.getName(), s);
                }
            }
            return Map.copyOf(out);
        } catch (Throwable t) {
            // WZ unreadable — behave as "no dialogue" rather than failing a tick.
            System.err.println("[PetDialogTable] failed to read PetDialog.img/" + petItemId + ": " + t);
            return Map.of();
        }
    }

    private static DataProvider stringProvider() {
        DataProvider p = stringProvider;
        if (p == null) {
            synchronized (PetDialogTable.class) {
                if (stringProvider == null) {
                    stringProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
                }
                p = stringProvider;
            }
        }
        return p;
    }
}
