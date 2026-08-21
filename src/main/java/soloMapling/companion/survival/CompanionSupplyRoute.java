package soloMapling.companion.survival;

/**
 * Conservative deterministic routing to potion shops already used by the
 * TrainingBot town loop.
 */
public final class CompanionSupplyRoute {
    private CompanionSupplyRoute() {
    }

    public static int potionShopFor(int mapId) {
        if (mapId >= 800_000_000 && mapId < 900_000_000) {
            return 800_000_000;
        }
        if (mapId >= 600_000_000 && mapId < 601_000_000) {
            return 600_000_000;
        }
        if (mapId >= 550_000_000 && mapId < 551_000_000) {
            return 550_000_000;
        }
        if (mapId >= 540_000_000 && mapId < 541_000_000) {
            return 540_000_000;
        }
        if (mapId >= 300_000_000 && mapId < 301_000_000) {
            return 300_000_000;
        }
        if (mapId >= 261_000_000 && mapId < 262_000_000) {
            return 261_000_000;
        }
        if (mapId >= 260_000_000 && mapId < 261_000_000) {
            return 260_000_000;
        }
        if (mapId >= 251_000_000 && mapId < 252_000_000) {
            return 251_000_000;
        }
        if (mapId >= 250_000_000 && mapId < 251_000_000) {
            return 250_000_002;
        }
        if (mapId >= 240_000_000 && mapId < 241_000_000) {
            return 240_000_002;
        }
        if (mapId >= 230_000_000 && mapId < 231_000_000) {
            return 230_000_002;
        }
        if (mapId >= 222_000_000 && mapId < 223_000_000) {
            return 222_000_000;
        }
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
