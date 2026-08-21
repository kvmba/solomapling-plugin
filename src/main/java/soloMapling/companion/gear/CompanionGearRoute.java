package soloMapling.companion.gear;

/** Conservative routes to v83 town weapon/armor shop maps. */
public final class CompanionGearRoute {
    private CompanionGearRoute() {
    }

    public static int equipmentShopFor(int mapId) {
        if (mapId >= 211_000_000 && mapId < 212_000_000) return 211_000_101;
        if (mapId >= 220_000_000 && mapId < 221_000_000) return 220_000_001;
        if (mapId >= 200_000_000 && mapId < 210_000_000) return 200_000_001;
        if (mapId >= 104_000_000 && mapId < 105_000_000) return 104_000_001;
        if (mapId >= 103_000_000 && mapId < 104_000_000) return 103_000_001;
        if (mapId >= 102_000_000 && mapId < 103_000_000) return 102_000_001;
        if (mapId >= 101_000_000 && mapId < 102_000_000) return 101_000_001;
        if (mapId >= 100_000_000 && mapId < 200_000_000) return 100_000_101;
        return -1;
    }
}
