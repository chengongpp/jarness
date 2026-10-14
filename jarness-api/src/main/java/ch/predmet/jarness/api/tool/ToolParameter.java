package ch.predmet.jarness.api.tool;

/**
 * One tool parameter, rendered into a provider JSON schema by the provider adapters.
 *
 * @param type        JSON schema type: {@code string}, {@code number}, {@code integer}, {@code boolean}, {@code object}, {@code array}
 * @param required    whether the model must supply it
 * @param description model-facing description, kept short because it costs prompt tokens
 */
public record ToolParameter(String type, boolean required, String description) {

    /** @return a required parameter of {@code type}. */
    public static ToolParameter required(String type, String description) {
        return new ToolParameter(type, true, description);
    }

    /** @return an optional parameter of {@code type}. */
    public static ToolParameter optional(String type, String description) {
        return new ToolParameter(type, false, description);
    }
}
