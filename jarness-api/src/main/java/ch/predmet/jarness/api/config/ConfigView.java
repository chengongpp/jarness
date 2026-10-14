package ch.predmet.jarness.api.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Read-only, dotted-path view over one parsed configuration document.
 *
 * <p>Paths address nested tables, e.g. {@code "models"}, {@code "limits.max_steps"} for TOML
 * {@code [limits] max_steps = 12}, and array entries by index, e.g. {@code "models.0.id"}.</p>
 */
public interface ConfigView {

    /** @return an empty view that has no keys. */
    static ConfigView empty() {
        return ch.predmet.jarness.api.config.spi.EmptyConfigView.INSTANCE;
    }

    /** @return whether this document has no keys at all. */
    boolean isEmpty();

    /** @return the top-level keys of this document. */
    Set<String> keys();

    /** @return the value at {@code path} if it is a textual or scalar value. */
    Optional<String> findString(String path);

    /** @return the text at {@code path}, or {@code fallback} when absent or not scalar. */
    default String string(String path, String fallback) {
        return findString(path).orElse(fallback);
    }

    /** @return the integer at {@code path} if present and numeric. */
    OptionalInt findInt(String path);

    /** @return the integer at {@code path}, or {@code fallback} when absent or unparsable. */
    default int integer(String path, int fallback) {
        return findInt(path).orElse(fallback);
    }

    /** @return the boolean at {@code path}, or {@code fallback} when absent. */
    boolean bool(String path, boolean fallback);

    /** @return the array at {@code path} rendered as strings; empty when absent. */
    List<String> stringList(String path);

    /** @return the nested table at {@code path}; an empty view when absent. */
    ConfigView table(String path);

    /** @return each element of the array of tables at {@code path}; empty when absent. */
    List<ConfigView> tables(String path);

    /** @return the raw JSON-tree behind this view, for escape hatches and diagnostics. */
    JsonNode raw();

    /** @return a shallow, printable map of the top level, used by diagnostics. */
    Map<String, Object> shallowMap();
}
