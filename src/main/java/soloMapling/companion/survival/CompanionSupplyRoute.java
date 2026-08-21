package soloMapling.companion.survival;

/**
 * Conservative deterministic routing to potion shops already used by the
 * TrainingBot town loop.
 */
public final class CompanionSupplyRoute {
    private CompanionSupplyRoute() {
    }

    public static int potionShopFor(int mapId) {
        if (mapId >= 211_000_000 && mapId < 212_000_000) {
            return 211_000_102;
        }
        if (mapId >= 220_000_000 && mapId < 221_000_000) {
            return 220_000_002;
        }
        if (mapId >= 200_000_000 && mapId < 210_000_000) {
            return 200_000_002;
        }
        if (mapId >= 104_000_000 && mapId < 105_000_000) {
            return 104_000_002;
        }
        if (mapId >= 103_000_000 && mapId < 104_000_000) {
            return 103_000_002;
        }
        if (mapId >= 102_000_000 && mapId < 103_000_000) {
            return 102_000_002;
        }
        if (mapId >= 101_000_000 && mapId < 102_000_000) {
            return 101_000_002;
        }
        if (mapId >= 100_000_000 && mapId < 200_000_000) {
            return 100_000_102;
        }
        return -1;
    }
}
