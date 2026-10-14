package ch.predmet.jarness.core.config;

import ch.predmet.jarness.api.config.ConfigView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * TOML parsing helpers backed by Jackson's {@link TomlMapper}.
 *
 * <p>A missing file is not an error — it yields {@link ConfigView#empty()} so callers can apply
 * defaults; a malformed file is an {@link IllegalStateException} naming the file.</p>
 */
public final class TomlFiles {

    private static final TomlMapper MAPPER = new TomlMapper();

    private TomlFiles() {
    }

    /**
     * Load a TOML file into a configuration view.
     *
     * @param file the TOML file
     * @return the parsed view, or {@link ConfigView#empty()} when the file does not exist
     * @throws IllegalStateException when the file exists but cannot be parsed
     */
    public static ConfigView load(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return ConfigView.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readAllBytes(file));
            return node == null ? ConfigView.empty() : new JsonConfigView(node);
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse TOML file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parse a TOML document into a configuration view.
     *
     * @param toml the TOML text
     * @return the parsed view
     * @throws IllegalStateException when the text is not valid TOML
     */
    public static ConfigView parse(String toml) {
        Objects.requireNonNull(toml, "toml");
        try {
            JsonNode node = MAPPER.readTree(toml);
            return node == null ? ConfigView.empty() : new JsonConfigView(node);
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse TOML: " + e.getMessage(), e);
        }
    }
}
