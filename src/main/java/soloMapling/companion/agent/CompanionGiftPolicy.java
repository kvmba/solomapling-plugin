package soloMapling.companion.agent;

import soloMapling.companion.persistence.CompanionRelationship;
import soloMapling.companion.persona.PersonaProfile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic final authority for persona- and relationship-aware gifts. */
public final class CompanionGiftPolicy {
    private CompanionGiftPolicy() {
    }

    public enum Generosity {
        GENEROUS,
        BALANCED,
        CAUTIOUS
    }

    public static Generosity generosity(PersonaProfile persona) {
        String traits = String.join(" ", persona.traits()).toLowerCase(Locale.ROOT);
        if (containsAny(traits, "generous", "kind", "giving", "warm", "loyal")) {
            return Generosity.GENEROUS;
        }
        if (containsAny(traits, "cautious", "guarded", "frugal", "reserved", "shy")) {
            return Generosity.CAUTIOUS;
        }
        return Generosity.values()[(int) Math.floorMod(persona.personaSeed(), 3L)];
    }

    public static Set<Integer> allowedItemIds(
            List<CompanionInventoryItem> inventory,
            Optional<CompanionGearGoal> gearGoal,
            PersonaProfile persona,
            Optional<CompanionRelationship> relationship) {
        if (!relationshipAllows(generosity(persona), relationship)) {
            return Set.of();
        }
        int protectedGoal = gearGoal.map(CompanionGearGoal::itemId).orElse(-1);
        Map<String, Integer> equippedScores = new HashMap<>();
        for (CompanionInventoryItem item : inventory) {
            if (item.equipped() && item.equipment()) {
                equippedScores.merge(item.equipType(), item.score(), Math::max);
            }
        }
        Set<Integer> allowed = new HashSet<>();
        for (CompanionInventoryItem item : inventory) {
            if (item.equipped() || !item.equipment() || !item.tradeable()
                    || item.itemId() == protectedGoal) {
                continue;
            }
            Integer equippedScore = equippedScores.get(item.equipType());
            if (equippedScore != null && item.score() <= equippedScore) {
                allowed.add(item.itemId());
            }
        }
        return Set.copyOf(allowed);
    }

    private static boolean relationshipAllows(
            Generosity generosity, Optional<CompanionRelationship> relationship) {
        if (relationship.isEmpty()) {
            return false;
        }
        CompanionRelationship value = relationship.orElseThrow();
        return switch (generosity) {
            case GENEROUS -> value.familiarity() >= 1 || value.interactionCount() >= 1;
            case BALANCED -> value.familiarity() >= 3 || value.trust() >= 1;
            case CAUTIOUS -> value.familiarity() >= 6 && value.trust() >= 2;
        };
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
