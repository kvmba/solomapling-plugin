package soloMapling.companion.persistence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryTagCodecTest {

    @Test
    void encodingIsStableAndRoundTripsArbitraryDelimiters() {
        Set<String> tags = Set.of("quest:npc|42", "alpha", "雪😀");

        String encoded = MemoryTagCodec.encode(tags);

        assertEquals("5:alpha12:quest:npc|423:雪😀", encoded);
        assertEquals(tags, MemoryTagCodec.decode(encoded));
        assertEquals(encoded, MemoryTagCodec.encode(Set.of("雪😀", "alpha", "quest:npc|42")));
    }

    @Test
    void emptySetRoundTripsAsEmptyString() {
        assertEquals("", MemoryTagCodec.encode(Set.of()));
        assertEquals(Set.of(), MemoryTagCodec.decode(""));
    }

    @Test
    void rejectsMalformedOrNonCanonicalValues() {
        assertThrows(IllegalArgumentException.class, () -> MemoryTagCodec.decode("3:ab"));
        assertThrows(IllegalArgumentException.class, () -> MemoryTagCodec.decode("x:tag"));
        assertThrows(IllegalArgumentException.class, () -> MemoryTagCodec.encode(Set.of(" padded ")));
    }
}
