package soloMapling.companion.gear;

/** Explorer job-family routes to v83 town weapon/armor shop maps. */
public final class CompanionGearRoute {
    private CompanionGearRoute() {
    }

    public static int equipmentShopForJob(int jobId) {
        return switch (jobId / 100) {
            case 1 -> 102_000_001; // Warrior: Perion
            case 2 -> 101_000_001; // Magician: Ellinia
            case 3 -> 100_000_101; // Bowman: Henesys
            case 4 -> 103_000_001; // Thief: Kerning City
            case 5 -> 120_000_200; // Pirate: Nautilus
            default -> -1;
        };
    }
}
