package soloMapling.companion.persona;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonaProfileTest {

    @Test
    void promptRenderingIsCanonicalAndStable() {
        Map<String, String> firstPreferences = new LinkedHashMap<>();
        firstPreferences.put("loot", "shares fairly");
        firstPreferences.put("combat", "protects allies");

        PersonaProfile first = new PersonaProfile(
                76123L,
                List.of("Warm", "Curious"),
                "Brief and encouraging",
                firstPreferences,
                List.of("Never insult players", "Avoid spoilers"));
        PersonaProfile second = new PersonaProfile(
                76123L,
                List.of("Curious", "Warm"),
                "Brief and encouraging",
                Map.of("combat", "protects allies", "loot", "shares fairly"),
                List.of("Avoid spoilers", "Never insult players"));

        String expected = """
                Persona seed: 76123
                Voice: Brief and encouraging
                Traits:
                - Curious
                - Warm
                Preferences:
                - combat: protects allies
                - loot: shares fairly
                Boundaries:
                - Avoid spoilers
                - Never insult players
                """;
        assertEquals(expected, first.renderPrompt());
        assertEquals(first, second);
        assertEquals(first.renderPrompt(), second.renderPrompt());
    }

    @Test
    void validatesAndDefensivelyCopiesInputs() {
        List<String> mutableTraits = new java.util.ArrayList<>(List.of("Loyal"));
        Map<String, String> mutablePreferences = new LinkedHashMap<>();
        mutablePreferences.put("pace", "patient");
        PersonaProfile profile = new PersonaProfile(
                42L, mutableTraits, "calm", mutablePreferences, List.of());

        mutableTraits.add("Changed later");
        mutablePreferences.put("loot", "greedy");

        assertEquals(List.of("Loyal"), profile.traits());
        assertEquals(Map.of("pace", "patient"), profile.preferences());
        assertThrows(UnsupportedOperationException.class,
                () -> profile.preferences().put("new", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> new PersonaProfile(-1L, List.of("kind"), "calm", Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PersonaProfile(1L, List.of(), "calm", Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PersonaProfile(
                        1L, List.of("kind"), "calm\nmalicious", Map.of(), List.of()));
    }
}
