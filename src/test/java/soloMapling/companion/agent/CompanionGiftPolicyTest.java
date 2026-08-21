package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;
import soloMapling.companion.persistence.CompanionRelationship;
import soloMapling.companion.persona.PersonaProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionGiftPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void allowsObsoleteTradeableEquipmentForKnownPlayer() {
        PersonaProfile persona = new PersonaProfile(
                1, List.of("loyal"), "warm", Map.of(), List.of());
        CompanionRelationship relationship = relationship(1, 0, 0);
        List<CompanionInventoryItem> inventory = List.of(
                item(100, true, true, "CAP", 20),
                item(101, false, true, "CAP", 10));

        assertEquals(java.util.Set.of(101), CompanionGiftPolicy.allowedItemIds(
                inventory, Optional.empty(), persona, Optional.of(relationship)));
    }

    @Test
    void protectsUpgradeGoalsAndRejectsStrangers() {
        PersonaProfile persona = new PersonaProfile(
                1, List.of("loyal"), "warm", Map.of(), List.of());
        List<CompanionInventoryItem> inventory = List.of(
                item(100, true, true, "CAP", 20),
                item(101, false, true, "CAP", 10));
        CompanionGearGoal goal = new CompanionGearGoal(
                101, "White Bandana", "CAP", 10, 100100, "Slime", 100000001, 10, false);

        assertTrue(CompanionGiftPolicy.allowedItemIds(
                inventory, Optional.of(goal), persona, Optional.of(relationship(10, 10, 10))).isEmpty());
        assertTrue(CompanionGiftPolicy.allowedItemIds(
                inventory, Optional.empty(), persona, Optional.empty()).isEmpty());
    }

    private static CompanionInventoryItem item(
            int id, boolean equipped, boolean tradeable, String type, int score) {
        return new CompanionInventoryItem(
                id, "item-" + id, equipped ? "EQUIPPED" : "EQUIP",
                (short) (equipped ? -1 : 1), 1, equipped, true, tradeable, type, score);
    }

    private static CompanionRelationship relationship(int familiarity, int trust, int affinity) {
        return new CompanionRelationship(
                1, 5, 7, "friend", familiarity, trust, affinity, 1,
                "", NOW, NOW, NOW);
    }
}
