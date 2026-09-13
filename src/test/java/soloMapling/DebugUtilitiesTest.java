package soloMapling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks {@link DebugUtilities#fmt} - the positional {@code {}} substitution. It used to be built on
 * {@code String.replaceFirst}, which is now a plain indexOf pass; these cases pin the observable
 * contract so the rewrite cannot silently drift.
 */
class DebugUtilitiesTest {

    @Test
    void substitutesEachPlaceholderInOrder() {
        assertEquals("Hello Julia, you have 42 items",
                DebugUtilities.fmt("Hello {}, you have {} items", "Julia", 42));
    }

    @Test
    void noPlaceholdersReturnsTheTemplateUnchanged() {
        assertEquals("nothing to do here", DebugUtilities.fmt("nothing to do here", "ignored"));
    }

    @Test
    void fewerArgsThanPlaceholdersLeavesTheTrailingPlaceholder() {
        assertEquals("a=1 b={}", DebugUtilities.fmt("a={} b={}", 1));
    }

    @Test
    void moreArgsThanPlaceholdersIsIgnored() {
        assertEquals("only 1", DebugUtilities.fmt("only {}", 1, 2, 3));
    }

    @Test
    void anArgumentContainingBracesIsNotRescanned() {
        // The first placeholder receives the literal "{}"; the second receives "x". Result is "{}-x".
        assertEquals("{}-x", DebugUtilities.fmt("{}-{}", "{}", "x"));
    }

    @Test
    void nullArgumentRendersAsTheLiteralNull() {
        assertEquals("v=null", DebugUtilities.fmt("v={}", (Object) null));
    }
}
