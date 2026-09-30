package soloMapling.ArtificialPlayer.SkillShowSystem;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill names from String.wz/Skill.img, lazily loaded once and memoized.
 * BotPlaceNames pattern: never throws, never returns null - an unresolvable
 * id falls back to the id itself so a bubble is always printable.
 */
public final class BotSkillNames {

    private BotSkillNames() {
    }

    private static volatile Map<Integer, String> names;
    private static volatile boolean attempted;

    public static String name(int skillId) {
        Map<Integer, String> table = table();
        String name = table.get(skillId);
        return name != null ? name : String.valueOf(skillId);
    }

    private static Map<Integer, String> table() {
        Map<Integer, String> cached = names;
        if (cached != null) {
            return cached;
        }
        if (!attempted) {
            synchronized (BotSkillNames.class) {
                if (!attempted) {
                    attempted = true;
                    names = build();
                    return names;
                }
            }
        }
        return Map.of(); // WZ unreadable — callers fall back to the id
    }

    private static Map<Integer, String> build() {
        Map<Integer, String> out = new ConcurrentHashMap<>();
        try {
            DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.STRING);
            Data root = provider.getData("Skill.img");
            if (root == null) {
                return out;
            }
            for (Data entry : root.getChildren()) {
                int id;
                try {
                    id = Integer.parseInt(entry.getName());
                } catch (NumberFormatException ignored) {
                    continue; // a job book node like "000", not a skill
                }
                String name = DataTool.getString("name", entry, null);
                if (name != null && !name.isBlank()) {
                    out.put(id, name);
                }
            }
        } catch (Exception e) {
            System.err.println("[BotSkillNames] Failed to load skill names from String.wz: "
                    + e.getMessage());
        }
        return out;
    }
}
