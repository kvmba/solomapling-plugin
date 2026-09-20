package soloMapling.ArtificialPlayer.BotChatterSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.ArtificialPlayer.DialoguePackPaths;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

// Loads TownChatterDialogue.yaml: ordered bot-to-bot small-talk exchanges. Unlike the single-line dialogue
// nodes served by BotDialogueHandler (one random line from a node), an exchange is an ORDERED list of
// alternating turns (A,B,A,B...), which is exactly what two-party chatter needs. YAML-first with a
// defensive fail-soft parse (bad edit -> empty list, BotChatter then falls back to its hardcoded exchange),
// same pattern as TownPresenceConfig. Our own creation (not a GreenCat extraction).
//
// Two scene sections share the file, published as ONE snapshot (a single volatile Pack, like
// BotMessages) so a first read parses the file once, not once per section:
//   exchanges: town small-talk — BotChatter's participants (SocialBot / TownWandererBot) run these on
//              ordinary town maps. Must stay town-agnostic (no vehicle nouns), see TownChatterSceneTest.
//   vehicle:   crossing small-talk — played only while a participant is aboard a vehicle map (see
//              BotChatter.pickExchange + GCTransit.isVehicleMap). Kept agnostic to the specific ride:
//              one pool serves boats, trains, planes, subways, the genie carpet and the elevator, so a
//              line must not name a vehicle/its crew/scene (a boat line must not play in a subway).
public final class TownChatterLines {

    private TownChatterLines() {
    }

    private static final String YAML_FILE = "TownChatterDialogue.yaml";
    private static final String TOWN_KEY = "exchanges";
    private static final String VEHICLE_KEY = "vehicle";

    private static final Random RANDOM = new Random();

    // The two parsed sections as one immutable pair, so a reader never sees one reloaded while the
    // other is stale. null until the first load; an empty-file/missing-file parse is cached too, so a
    // bad pack is not re-read on every call (same discipline as the original single-list cache).
    private static final class Pack {
        final List<List<String>> town;
        final List<List<String>> vehicle;

        Pack(List<List<String>> town, List<List<String>> vehicle) {
            this.town = town;
            this.vehicle = vehicle;
        }
    }

    private static volatile Pack pack;

    // Town exchanges (≥ 2 turns each). Empty list on any parse/IO failure.
    public static List<List<String>> exchanges() {
        return pack().town;
    }

    // Vehicle-aboard exchanges (≥ 2 turns each). Empty list when the file has no vehicle section.
    public static List<List<String>> vehicleExchanges() {
        return pack().vehicle;
    }

    // Force a re-read from disk (used by the live-tuning !env chatter command so edits apply without a restart).
    public static List<List<String>> reload() {
        Pack parsed = parse();
        pack = parsed;
        return parsed.town;
    }

    // A random town exchange (>= 2 turns), or null if none are loaded.
    public static List<String> randomExchange() {
        return pickRandom(exchanges());
    }

    // A random vehicle-aboard exchange (>= 2 turns), or null if none are loaded.
    public static List<String> randomVehicleExchange() {
        return pickRandom(vehicleExchanges());
    }

    private static List<String> pickRandom(List<List<String>> all) {
        if (all.isEmpty()) {
            return null;
        }
        return all.get(RANDOM.nextInt(all.size()));
    }

    // Current snapshot, parsing once on first use; a failed/empty parse is cached as an empty Pack. A
    // losing race just rebuilds an equivalent Pack.
    private static Pack pack() {
        Pack current = pack;
        if (current != null) {
            return current;
        }
        synchronized (TownChatterLines.class) {
            if (pack == null) {
                pack = parse();
            }
            return pack;
        }
    }

    @SuppressWarnings("unchecked")
    private static Pack parse() {
        List<List<String>> town = new ArrayList<>();
        List<List<String>> vehicle = new ArrayList<>();
        try (Reader fileReader = DialoguePackPaths.openDialogueReader(YAML_FILE)) {
            YamlReader reader = new YamlReader(fileReader);
            Map<String, Object> root = (Map<String, Object>) reader.read();
            if (root != null) {
                parseSection(root.get(TOWN_KEY), town);
                parseSection(root.get(VEHICLE_KEY), vehicle);
            }
        } catch (Exception e) {
            System.out.println("[TownChatterLines] failed to load " + YAML_FILE + ": " + e.getMessage());
        }
        return new Pack(List.copyOf(town), List.copyOf(vehicle));
    }

    // One YAML section (a list of ordered exchanges) into out; entries with fewer than 2 clean turns
    // are dropped. Tolerates a missing/non-list section (leaves out untouched).
    private static void parseSection(Object node, List<List<String>> out) {
        if (!(node instanceof List<?> list)) {
            return;
        }
        for (Object ex : list) {
            if (!(ex instanceof List<?> turns)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (Object t : turns) {
                if (t != null) {
                    String s = t.toString().trim();
                    if (!s.isEmpty()) {
                        lines.add(s);
                    }
                }
            }
            if (lines.size() >= 2) {
                out.add(lines);
            }
        }
    }
}
