package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.mod.DependencyKind;
import ch.predmet.jarness.api.mod.ModDependency;
import ch.predmet.jarness.api.mod.ModDescriptor;
import ch.predmet.jarness.api.mod.ModOrder;
import ch.predmet.jarness.core.config.TomlFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parses {@code META-INF/jnmods.toml} mod descriptors into {@link ModDescriptor}s.
 *
 * <p>Malformed documents, missing {@code id}, and unknown dependency {@code kind}/{@code ordering}
 * values raise {@link IllegalArgumentException} naming the offending value.</p>
 */
public final class JnmodsParser {

    private JnmodsParser() {
    }

    /**
     * Parse a {@code jnmods.toml} document.
     *
     * @param toml the document text
     * @return the parsed descriptor (never {@code system}, never {@code required})
     * @throws IllegalArgumentException when the document is malformed
     */
    public static ModDescriptor parse(String toml) {
        Objects.requireNonNull(toml, "toml");
        ConfigView view;
        try {
            view = TomlFiles.parse(toml);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("invalid jnmods.toml: " + e.getMessage(), e);
        }
        String id = view.findString("id")
                .orElseThrow(() -> new IllegalArgumentException("jnmods.toml is missing the required 'id' key"));
        String name = view.findString("name").orElse(null);
        String version = view.findString("version").orElse(null);
        String description = view.findString("description").orElse(null);
        String entrypoint = view.findString("entrypoint").orElse(null);

        List<ModDependency> dependencies = new ArrayList<>();
        int index = 0;
        for (ConfigView depView : view.tables("dependencies")) {
            int depIndex = index++;
            String depId = depView.findString("modId").orElseThrow(() ->
                    new IllegalArgumentException("dependencies entry #" + depIndex + " is missing 'modId'"));
            String range = depView.findString("version").orElse("*");
            DependencyKind kind = depView.findString("kind")
                    .map(JnmodsParser::parseKind)
                    .orElse(DependencyKind.REQUIRED);
            ModOrder ordering = depView.findString("ordering")
                    .map(JnmodsParser::parseOrdering)
                    .orElse(ModOrder.NONE);
            String reason = depView.findString("reason").orElse(null);
            dependencies.add(new ModDependency(depId, range, kind, ordering, reason));
        }
        String apiVersion = view.string("api_version", view.string("apiVersion", "1"));
        return new ModDescriptor(id, name, version, apiVersion, description, entrypoint, dependencies, false, false);
    }

    /**
     * Parse a {@code jnmods.toml} document from a stream.
     *
     * @param in the stream; read fully, not closed
     * @return the parsed descriptor
     * @throws IllegalArgumentException when the document is malformed or unreadable
     */
    public static ModDescriptor parse(InputStream in) {
        Objects.requireNonNull(in, "in");
        try {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read jnmods.toml: " + e.getMessage(), e);
        }
    }

    private static DependencyKind parseKind(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "required" -> DependencyKind.REQUIRED;
            case "optional" -> DependencyKind.OPTIONAL;
            case "incompatible" -> DependencyKind.INCOMPATIBLE;
            default -> throw new IllegalArgumentException(
                    "unknown dependency kind \"" + value + "\" (expected required|optional|incompatible)");
        };
    }

    private static ModOrder parseOrdering(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> ModOrder.NONE;
            case "before" -> ModOrder.BEFORE;
            case "after" -> ModOrder.AFTER;
            default -> throw new IllegalArgumentException(
                    "unknown dependency ordering \"" + value + "\" (expected none|before|after)");
        };
    }
}
