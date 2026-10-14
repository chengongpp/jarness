package ch.predmet.jarness.core;

import ch.predmet.jarness.api.util.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the mod-dependency version matcher. */
class VersionTest {

    @Test
    void parsesAndCompares() {
        assertEquals(0, Version.parse("1.2").compareTo(Version.parse("1.2.0")));
        assertTrue(Version.parse("1.10").compareTo(Version.parse("1.9")) > 0);
        assertTrue(Version.parse("2.0").compareTo(Version.parse("10.0")) < 0);
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse("  "));
    }

    @Test
    void wildcardAcceptsEverything() {
        assertTrue(Version.satisfies("1.0", "*"));
        assertTrue(Version.satisfies("0.0.1", ""));
        assertTrue(Version.satisfies("3.4.5", null));
    }

    @Test
    void exactMatches() {
        assertTrue(Version.satisfies("1.2.3", "1.2.3"));
        assertFalse(Version.satisfies("1.2.4", "1.2.3"));
    }

    @Test
    void xWildcard() {
        assertTrue(Version.satisfies("1.2.9", "1.2.x"));
        assertFalse(Version.satisfies("1.3.0", "1.2.x"));
        assertTrue(Version.satisfies("2.5.1", "2.x"));
    }

    @Test
    void caretRange() {
        assertTrue(Version.satisfies("1.4.0", "^1.2.3"));
        assertFalse(Version.satisfies("2.0.0", "^1.2.3"));
        assertFalse(Version.satisfies("1.2.2", "^1.2.3"));
        // 0-major: caret locks the minor
        assertTrue(Version.satisfies("0.2.9", "^0.2.3"));
        assertFalse(Version.satisfies("0.3.0", "^0.2.3"));
    }

    @Test
    void tildeRange() {
        assertTrue(Version.satisfies("1.2.9", "~1.2.3"));
        assertFalse(Version.satisfies("1.3.0", "~1.2.3"));
    }

    @Test
    void comparisonRanges() {
        assertTrue(Version.satisfies("1.5", ">=1.2"));
        assertFalse(Version.satisfies("1.1", ">=1.2"));
        assertTrue(Version.satisfies("1.2", "<=1.2"));
        assertTrue(Version.satisfies("0.9", "<1.0"));
        assertTrue(Version.satisfies("1.0", "=1.0"));
    }

    @Test
    void mavenIntervals() {
        assertTrue(Version.satisfies("1.5", "[1.0,2.0)"));
        assertFalse(Version.satisfies("2.0", "[1.0,2.0)"));
        assertTrue(Version.satisfies("2.0", "[1.0,2.0]"));
        assertTrue(Version.satisfies("9.9", "[1.0,)"));
        assertFalse(Version.satisfies("0.9", "(0.9,2.0]"));
    }

    @Test
    void commaClauses() {
        assertTrue(Version.satisfies("1.5", ">=1.0,<2.0"));
        assertFalse(Version.satisfies("2.0", ">=1.0,<2.0"));
    }
}
