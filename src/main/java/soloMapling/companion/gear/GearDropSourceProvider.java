package soloMapling.companion.gear;

import java.util.List;

/**
 * Host-independent seam for drop-table data.
 */
@FunctionalInterface
public interface GearDropSourceProvider {
    List<DropSource> sourcesFor(int itemId);

    record DropSource(
            int mobId,
            String mobName,
            int mapId,
            String mapName,
            double chance,
            boolean boss) {
    }
}
