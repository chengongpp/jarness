package ch.predmet.jarness.api.namespace;

import java.util.Map;
import java.util.Optional;

/**
 * Namespace-level policy knobs, kept as a typed bag so policies can land without breaking the
 * namespace record.
 *
 * <p>The approval policy belongs here ({@code approval = "never" | "ask" | "allow"}), together with
 * the allow-list. Tool dispatch reads it for every mutating tool; ASK currently waits synchronously
 * on a registered approval handler rather than suspending through the mailbox.</p>
 *
 * @param values policy key/value pairs
 */
public record PolicySet(Map<String, Object> values) {

    public PolicySet {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** @return an empty policy set. */
    public static PolicySet empty() {
        return new PolicySet(Map.of());
    }

    /** @return the value under {@code key}. */
    public Optional<Object> get(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(values.get(key));
    }

    /** @return the string value under {@code key}, or {@code fallback}. */
    public String string(String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
