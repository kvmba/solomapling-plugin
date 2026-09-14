package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-pet interaction data, read from {@code Item.wz/Pet/<id>.img/interact}.
 *
 * <p>Every pet carries its own behaviour menu in WZ: each {@code interact/<N>}
 * entry is one command the pet can perform, with a trigger probability
 * ({@code prob} — the chance the pet OBEYS it, in percent), the pet-level band
 * it belongs to ({@code l0}..{@code l1}), and a {@code success}/{@code fail}
 * sub-tree whose {@code act} is the animation and whose numeric children are the
 * speech keys the client resolves against {@code String.wz/PetDialog.img}.</p>
 *
 * <p>The server does not need the speech text: it tells the client which command
 * to play ({@code PacketCreator.commandResponse}) and the client shows that pet's
 * own behaviour line from its local WZ. We only need the index, the probability
 * and the level band to pick a plausible command for the pet — exactly the data
 * loaded here.</p>
 *
 * <p>Lazy and best-effort: a pet with no readable WZ yields an empty list and the
 * caller simply does nothing, so a missing/short WZ can never break a bot tick.</p>
 */
public final class PetInteractionTable {

    /** One pet command: its interact index, trigger prob (%), and pet-level band. */
    public record Interact(int index, int prob, int l0, int l1) {
        boolean allowsLevel(int petLevel) {
            return petLevel >= l0 && petLevel <= l1;
        }
    }

    private static final Map<Integer, List<Interact>> CACHE = new ConcurrentHashMap<>();
    private static volatile DataProvider itemWz;

    private PetInteractionTable() {
    }

    /** Commands for this pet item id, or an empty list when WZ has none. */
    public static List<Interact> interactionsFor(int petItemId) {
        List<Interact> cached = CACHE.get(petItemId);
        if (cached != null) {
            return cached;
        }
        List<Interact> loaded = load(petItemId);
        CACHE.put(petItemId, loaded);
        return loaded;
    }

    private static List<Interact> load(int petItemId) {
        List<Interact> out = new ArrayList<>();
        try {
            DataProvider provider = itemWz();
            if (provider == null) {
                return out;
            }
            Data root = provider.getData("Pet/" + petItemId + ".img");
            if (root == null) {
                return out;
            }
            Data interact = root.getChildByPath("interact");
            if (interact == null) {
                return out;
            }
            for (Data entry : interact.getChildren()) {
                Integer index = asInt(entry.getName());
                if (index == null) {
                    continue;
                }
                int prob = DataTool.getInt("prob", entry, 0);
                int l0 = DataTool.getInt("l0", entry, 0);
                int l1 = DataTool.getInt("l1", entry, 200);
                out.add(new Interact(index, prob, l0, l1));
            }
        } catch (Throwable t) {
            // WZ unreadable — behave as "no interactions" rather than failing a tick.
            System.err.println("[PetInteractionTable] failed to read Pet/" + petItemId + ".img: " + t);
        }
        return List.copyOf(out);
    }

    private static DataProvider itemWz() {
        DataProvider p = itemWz;
        if (p == null) {
            synchronized (PetInteractionTable.class) {
                if (itemWz == null) {
                    itemWz = DataProviderFactory.getDataProvider(WZFiles.ITEM);
                }
                p = itemWz;
            }
        }
        return p;
    }

    private static Integer asInt(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
