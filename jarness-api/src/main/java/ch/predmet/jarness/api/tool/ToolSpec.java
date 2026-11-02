package ch.predmet.jarness.api.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The model-facing contract of one tool.
 *
 * <p>Names, parameter names, and descriptions are written to match deepseek-harness conventions on
 * purpose: models in this family are post-trained on that vocabulary, so staying close to it buys
 * reliable tool calling for free.</p>
 *
 * @param name        tool name, unique in the registry
 * @param description one-to-three sentence model-facing description
 * @param parameters  ordered parameter declarations
 * @param readOnly    whether the call is free of externally visible side effects; mutating tools are
 *                    subject to the namespace approval policy regardless of their capability kinds
 */
public record ToolSpec(String name, String description, Map<String, ToolParameter> parameters, boolean readOnly) {

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        description = description == null ? "" : description;
        parameters = parameters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parameters));
    }

    /** @return a read-only tool spec. */
    public static ToolSpec readOnly(String name, String description, Map<String, ToolParameter> parameters) {
        return new ToolSpec(name, description, parameters, true);
    }

    /** @return a mutating tool spec. */
    public static ToolSpec mutating(String name, String description, Map<String, ToolParameter> parameters) {
        return new ToolSpec(name, description, parameters, false);
    }

    /** @return a spec with no parameters. */
    public static ToolSpec of(String name, String description) {
        return new ToolSpec(name, description, Map.of(), true);
    }
}
