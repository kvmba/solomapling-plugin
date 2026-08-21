package soloMapling.companion.persistence;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stable, reversible tag encoding: each sorted tag is written as
 * {@code <UTF-16 length>:<tag>}. Length prefixes permit arbitrary delimiters.
 */
final class MemoryTagCodec {

    private MemoryTagCodec() {
    }

    static String encode(Set<String> tags) {
        Objects.requireNonNull(tags, "tags must not be null");
        TreeSet<String> canonical = new TreeSet<>();
        for (String tag : tags) {
            canonical.add(requireTag(tag));
        }

        StringBuilder encoded = new StringBuilder();
        for (String tag : canonical) {
            encoded.append(tag.length()).append(':').append(tag);
        }
        return encoded.toString();
    }

    static Set<String> decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded tags must not be null");
        TreeSet<String> tags = new TreeSet<>();
        int cursor = 0;
        while (cursor < encoded.length()) {
            int separator = encoded.indexOf(':', cursor);
            if (separator == -1 || separator == cursor) {
                throw malformed(encoded);
            }

            int length;
            try {
                length = Integer.parseInt(encoded.substring(cursor, separator));
            } catch (NumberFormatException exception) {
                throw malformed(encoded);
            }
            int start = separator + 1;
            int end = start + length;
            if (length <= 0 || end < start || end > encoded.length()) {
                throw malformed(encoded);
            }
            tags.add(requireTag(encoded.substring(start, end)));
            cursor = end;
        }
        return Collections.unmodifiableSet(tags);
    }

    private static String requireTag(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("tag must not be blank");
        }
        String normalized = tag.trim();
        if (!normalized.equals(tag)) {
            throw new IllegalArgumentException("tag must already be normalized");
        }
        return normalized;
    }

    private static IllegalArgumentException malformed(String encoded) {
        return new IllegalArgumentException("Malformed tag encoding: " + encoded);
    }
}
