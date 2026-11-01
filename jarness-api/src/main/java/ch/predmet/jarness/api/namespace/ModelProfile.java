package ch.predmet.jarness.api.namespace;

import ch.predmet.jarness.api.provider.ModelRef;

import java.util.Map;
import java.util.Objects;

/**
 * A model tier: which provider/model a namespace prefers and the sampling defaults that go with it.
 *
 * <p>Model selection lives at the namespace level on purpose. It is a property of the machine and
 * the workload (fast small model for cheap namespaces, a large model for the one that needs it),
 * not of the executable definition of an agent. An {@link ch.predmet.jarness.api.agent.AgentManifest}
 * may override the profile at spawn time; an {@code AgentTemplate} never carries a model.</p>
 *
 * @param id         profile id, unique within a namespace
 * @param model      the provider/model reference
 * @param parameters sampling defaults such as {@code temperature} or {@code max_tokens}
 */
public record ModelProfile(String id, ModelRef model, Map<String, Object> parameters) {

    public ModelProfile {
        Objects.requireNonNull(model, "model");
        id = id == null || id.isBlank() ? "default" : id;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** @return a profile named {@code default} for {@code model}. */
    public static ModelProfile of(ModelRef model) {
        return new ModelProfile("default", model, Map.of());
    }

    /** @return the parameter as a double, or {@code fallback}. */
    public double doubleParam(String key, double fallback) {
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
