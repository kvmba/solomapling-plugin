package soloMapling.companion.persona;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, schema-independent description of a companion's persona.
 */
public record PersonaProfile(
        long personaSeed,
        List<String> traits,
        String voice,
        Map<String, String> preferences,
        List<String> boundaries) {

    public PersonaProfile {
        if (personaSeed < 0) {
            throw new IllegalArgumentException("personaSeed must not be negative");
        }
        voice = requireText(voice, "voice");
        traits = canonicalList(traits, "traits", true);
        boundaries = canonicalList(boundaries, "boundaries", false);
        preferences = canonicalMap(preferences);
    }

    /**
     * Renders a canonical prompt fragment. Collection ordering is independent of
     * the iteration order supplied to the constructor.
     */
    public String renderPrompt() {
        StringBuilder prompt = new StringBuilder()
                .append("Persona seed: ").append(personaSeed).append('\n')
                .append("Voice: ").append(voice).append('\n')
                .append("Traits:\n");
        traits.forEach(trait -> prompt.append("- ").append(trait).append('\n'));

        prompt.append("Preferences:\n");
        preferences.forEach((key, value) ->
                prompt.append("- ").append(key).append(": ").append(value).append('\n'));

        prompt.append("Boundaries:\n");
        if (boundaries.isEmpty()) {
            prompt.append("- none\n");
        } else {
            boundaries.forEach(boundary ->
                    prompt.append("- ").append(boundary).append('\n'));
        }
        return prompt.toString();
    }

    private static List<String> canonicalList(
            List<String> values, String field, boolean requireNonEmpty) {
        Objects.requireNonNull(values, field + " must not be null");
        List<String> canonical = new ArrayList<>(values.size());
        for (String value : values) {
            canonical.add(requireText(value, field + " entry"));
        }
        canonical = canonical.stream().distinct().sorted().toList();
        if (requireNonEmpty && canonical.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return canonical;
    }

    private static Map<String, String> canonicalMap(Map<String, String> values) {
        Objects.requireNonNull(values, "preferences must not be null");
        TreeMap<String, String> canonical = new TreeMap<>();
        values.forEach((key, value) -> canonical.put(
                requireText(key, "preference key"),
                requireText(value, "preference value")));
        return Collections.unmodifiableMap(canonical);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.chars().anyMatch(character -> character == '\n' || character == '\r')) {
            throw new IllegalArgumentException(field + " must be a single line");
        }
        return normalized;
    }
}
