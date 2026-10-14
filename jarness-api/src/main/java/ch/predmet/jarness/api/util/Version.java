package ch.predmet.jarness.api.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A dotted version with optional qualifier, plus Maven-ish range matching for mod dependencies.
 *
 * <p>Supported range syntaxes, enough for mod metadata without pulling in a dependency resolver:</p>
 * <ul>
 *   <li>{@code *} or empty — any version</li>
 *   <li>{@code 1.2.3} — exactly that version</li>
 *   <li>{@code 1.2.x} or {@code 1.2.*} — any patch level of 1.2</li>
 *   <li>{@code ^1.2.3} — {@code >=1.2.3} and same major (or same minor when major is 0)</li>
 *   <li>{@code ~1.2.3} — {@code >=1.2.3} and same minor</li>
 *   <li>{@code >=1.2}, {@code >1.2}, {@code <=2}, {@code <2}, {@code =1.2}</li>
 *   <li>{@code [1.0,2.0)}, {@code (1.0,2.0]}, {@code [1.0,)} — interval notation</li>
 * </ul>
 */
public final class Version implements Comparable<Version> {

    private final String raw;
    private final List<Integer> numbers;
    private final String qualifier;

    private Version(String raw, List<Integer> numbers, String qualifier) {
        this.raw = raw;
        this.numbers = List.copyOf(numbers);
        this.qualifier = qualifier;
    }

    /**
     * Parse a version string. Missing components count as zero, so {@code 1.2} equals {@code 1.2.0}.
     *
     * @param text the version text
     * @return the parsed version
     */
    public static Version parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        String qualifier = "";
        int dash = trimmed.indexOf('-');
        String numeric = trimmed;
        if (dash >= 0) {
            numeric = trimmed.substring(0, dash);
            qualifier = trimmed.substring(dash + 1).toLowerCase(Locale.ROOT);
        }
        String[] parts = numeric.split("\\.");
        List<Integer> numbers = new ArrayList<>(parts.length);
        for (String part : parts) {
            String digits = part.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                numbers.add(0);
            } else {
                try {
                    numbers.add(Integer.parseInt(digits));
                } catch (NumberFormatException e) {
                    numbers.add(0);
                }
            }
        }
        while (numbers.size() < 3) {
            numbers.add(0);
        }
        return new Version(trimmed, numbers, qualifier);
    }

    private int part(int index) {
        return index < numbers.size() ? numbers.get(index) : 0;
    }

    /** @return the major component. */
    public int major() {
        return part(0);
    }

    /** @return the minor component. */
    public int minor() {
        return part(1);
    }

    /** @return the patch component. */
    public int patch() {
        return part(2);
    }

    /** @return the qualifier after {@code -}, or an empty string. */
    public String qualifier() {
        return qualifier;
    }

    /**
     * Test this version against a range expression.
     *
     * @param range the range text; {@code null} or blank means "any"
     * @return whether this version satisfies the range
     */
    public boolean satisfies(String range) {
        if (range == null || range.isBlank() || range.trim().equals("*")) {
            return true;
        }
        String text = range.trim();
        if (text.startsWith("[") || text.startsWith("(")) {
            return satisfiesInterval(text);
        }
        for (String clause : text.split(",")) {
            if (!satisfiesClause(clause.trim())) {
                return false;
            }
        }
        return true;
    }

    private boolean satisfiesInterval(String text) {
        boolean lowerInclusive = text.startsWith("[");
        boolean upperInclusive = text.endsWith("]");
        String body = text.substring(1, text.length() - 1);
        String[] bounds = body.split(",", -1);
        if (bounds.length != 2) {
            throw new IllegalArgumentException("malformed version range \"" + text + "\"");
        }
        String lower = bounds[0].trim();
        String upper = bounds[1].trim();
        if (!lower.isEmpty()) {
            int cmp = compareTo(parse(lower));
            if (lowerInclusive ? cmp < 0 : cmp <= 0) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int cmp = compareTo(parse(upper));
            if (upperInclusive ? cmp > 0 : cmp >= 0) {
                return false;
            }
        }
        return true;
    }

    private boolean satisfiesClause(String clause) {
        if (clause.isEmpty() || clause.equals("*")) {
            return true;
        }
        if (clause.startsWith(">=")) {
            return compareTo(parse(clause.substring(2))) >= 0;
        }
        if (clause.startsWith("<=")) {
            return compareTo(parse(clause.substring(2))) <= 0;
        }
        if (clause.startsWith(">")) {
            return compareTo(parse(clause.substring(1))) > 0;
        }
        if (clause.startsWith("<")) {
            return compareTo(parse(clause.substring(1))) < 0;
        }
        if (clause.startsWith("=")) {
            return equalsVersion(parse(clause.substring(1)));
        }
        if (clause.startsWith("^")) {
            Version base = parse(clause.substring(1));
            return compareTo(base) >= 0 && compareTo(base.caretUpperBound()) < 0;
        }
        if (clause.startsWith("~")) {
            Version base = parse(clause.substring(1));
            return compareTo(base) >= 0 && compareTo(base.tildeUpperBound()) < 0;
        }
        if (clause.contains("x") || clause.contains("X") || clause.contains("*")) {
            String[] wanted = clause.toLowerCase(Locale.ROOT).split("\\.");
            for (int i = 0; i < wanted.length; i++) {
                if (wanted[i].equals("x") || wanted[i].equals("*")) {
                    return true;
                }
                if (part(i) != Integer.parseInt(wanted[i].replaceAll("[^0-9]", "0"))) {
                    return false;
                }
            }
            return true;
        }
        return equalsVersion(parse(clause));
    }

    private Version caretUpperBound() {
        if (major() > 0) {
            return new Version((major() + 1) + ".0.0", List.of(major() + 1, 0, 0), "");
        }
        return new Version("0." + (minor() + 1) + ".0", List.of(0, minor() + 1, 0), "");
    }

    private Version tildeUpperBound() {
        return new Version(major() + "." + (minor() + 1) + ".0", List.of(major(), minor() + 1, 0), "");
    }

    private boolean equalsVersion(Version other) {
        return compareTo(other) == 0;
    }

    @Override
    public int compareTo(Version other) {
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(part(i), other.part(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        if (qualifier.equals(other.qualifier)) {
            return 0;
        }
        if (qualifier.isEmpty()) {
            return 1;
        }
        if (other.qualifier.isEmpty()) {
            return -1;
        }
        return qualifier.compareTo(other.qualifier);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Version version && compareTo(version) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(part(0), part(1), part(2), qualifier);
    }

    @Override
    public String toString() {
        return raw;
    }

    /**
     * Convenience range test.
     *
     * @param version the version text
     * @param range   the range text
     * @return whether {@code version} satisfies {@code range}
     */
    public static boolean satisfies(String version, String range) {
        return parse(version).satisfies(range);
    }
}
