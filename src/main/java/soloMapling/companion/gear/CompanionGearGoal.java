package soloMapling.companion.gear;

/**
 * One actionable level-40+ equipment target and its best known drop source.
 */
public record CompanionGearGoal(
        String itemName,
        int itemId,
        CompanionGearPolicy.Slot slot,
        int requiredLevel,
        int mobId,
        String mobName,
        int mapId,
        String mapName,
        double chance,
        boolean boss) {
}
