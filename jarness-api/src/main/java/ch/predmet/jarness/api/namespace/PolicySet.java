package ch.predmet.jarness.api.namespace;

import java.util.Map;
import java.util.Optional;

/**
 * Namespace-level policy knobs, kept as a typed bag so policies can land without breaking the
 * namespace record.
 *
 * <p>The approval policy belongs here ({@code approval = "never" | "ask" | "allow"}), but nothing
 * in the runtime reads it yet: asynchronous approval needs the mailbox that arrives with the
 * collaboration layer. Until then the value is stored, shown by diagnostics, and ignored by tool
 * dispatch.</p>
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
