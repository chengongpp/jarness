package ch.predmet.jarness.api.namespace;

import ch.predmet.jarness.api.capability.CapabilitySet;

import java.util.Map;
import java.util.Objects;

/**
 * The "what is this namespace" half of a namespace: its name, labels, model tier, policies, and
 * the capability ceiling every agent in it is bounded by.
 *
 * <p>{@link #defaults()} is the {@code ns.defaults} term of the spawn intersection. An agent can
 * never hold a capability the namespace does not grant, no matter what its parent holds.</p>
 *
 * @param name     display name
 * @param labels   free-form labels used for routing and diagnostics
 * @param model    the default model profile, or {@code null} to fall back to the runtime default
 * @param profiles additional named profiles selectable by {@code manifest.modelProfileId}
 * @param policies namespace policy knobs
 * @param defaults the capability ceiling for agents in this namespace
 */
public record NamespaceMetadata(String name, Map<String, String> labels, ModelProfile model,
                                Map<String, ModelProfile> profiles, PolicySet policies,
                                CapabilitySet defaults) {

    public NamespaceMetadata {
        name = name == null || name.isBlank() ? "namespace" : name;
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
        policies = policies == null ? PolicySet.empty() : policies;
        defaults = defaults == null ? CapabilitySet.empty() : defaults;
    }

    /** @return metadata with just a name and a capability ceiling. */
    public static NamespaceMetadata of(String name, CapabilitySet defaults) {
        return new NamespaceMetadata(name, Map.of(), null, Map.of(), PolicySet.empty(), defaults);
    }

    /**
     * Resolve a model profile by id.
     *
     * @param profileId profile id, or {@code null}/blank for the default profile
     * @return the profile, or {@code null} when unknown and no default applies
     */
    public ModelProfile profile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return model;
        }
        ModelProfile explicit = profiles.get(profileId);
        if (explicit != null) {
            return explicit;
        }
        return model != null && model.id().equals(profileId) ? model : null;
    }

    /** @return a copy with a different capability ceiling. */
    public NamespaceMetadata withDefaults(CapabilitySet newDefaults) {
        return new NamespaceMetadata(name, labels, model, profiles, policies, newDefaults);
    }

    /** @return a copy bound to {@code newModel}. */
    public NamespaceMetadata withModel(ModelProfile newModel) {
        Objects.requireNonNull(newModel, "newModel");
        return new NamespaceMetadata(name, labels, newModel, profiles, policies, defaults);
    }
}
