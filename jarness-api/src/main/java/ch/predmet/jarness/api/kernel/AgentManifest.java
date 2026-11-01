package ch.predmet.jarness.api.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The declarative description of one agent: what to instantiate, under which policy, and with which
 * model tier.
 *
 * <p>A manifest is data, not behavior. It says "run template {@code standard}, restart on failure,
 * kill my subtree if I die, prefer model profile {@code fast}"; the supervisor resolves the
 * template and the namespace's {@link ch.predmet.jarness.api.namespace.ModelProfile} against it.
 * The session image and the agent's budget are separate inputs to spawn, which is why a manifest
 * can be stored and replayed.</p>
 *
 * @param name             display name, or {@code null} to derive one
 * @param modId            the mod that declared this agent
 * @param loopRef          agent-loop reference; {@code default} is the only loop in P0
 * @param restart          restart policy
 * @param orphan           what happens to children when this agent dies
 * @param origin           how the agent was born
 * @param templateId       template to instantiate, or {@code null} for the namespace default
 * @param templateVersion  required template version, or empty for "any"
 * @param modelProfileId   namespace model profile to use, or {@code null} for the namespace default
 * @param metadata         extension metadata
 */
public record AgentManifest(String name, String modId, String loopRef, RestartPolicy restart,
                            OrphanPolicy orphan, Origin origin, String templateId,
                            String templateVersion, String modelProfileId, Map<String, Object> metadata) {

    public AgentManifest {
        modId = modId == null || modId.isBlank() ? "jarness.core" : modId;
        loopRef = loopRef == null || loopRef.isBlank() ? "default" : loopRef;
        restart = restart == null ? RestartPolicy.NO : restart;
        orphan = orphan == null ? OrphanPolicy.KILL : orphan;
        origin = origin == null ? Origin.FRESH : origin;
        templateId = templateId == null || templateId.isBlank() ? null : templateId.trim();
        templateVersion = templateVersion == null ? "" : templateVersion.trim();
        modelProfileId = modelProfileId == null || modelProfileId.isBlank() ? null : modelProfileId.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** @return a fresh manifest for {@code templateId} with default policies. */
    public static AgentManifest of(String templateId) {
        return new AgentManifest(null, null, null, null, null, null, templateId, "", null, Map.of());
    }

    /** @return a builder for a fresh manifest of {@code templateId}. */
    public static Builder builder(String templateId) {
        return new Builder(templateId);
    }

    /**
     * @param fallback the template to use when this manifest names none
     * @return the template id this manifest resolves to
     */
    public String templateIdOr(String fallback) {
        return templateId == null ? fallback : templateId;
    }

    /** @return a copy marked as a fork. */
    public AgentManifest asFork() {
        return new AgentManifest(name, modId, loopRef, restart, orphan, Origin.FORK, templateId,
                templateVersion, modelProfileId, metadata);
    }

    /** Fluent builder for {@link AgentManifest}. */
    public static final class Builder {

        private String name;
        private String modId;
        private String loopRef;
        private RestartPolicy restart;
        private OrphanPolicy orphan;
        private Origin origin = Origin.FRESH;
        private String templateId;
        private String templateVersion;
        private String modelProfileId;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String templateId) {
            this.templateId = templateId;
        }

        /** @return this builder. */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** @return this builder. */
        public Builder modId(String value) {
            this.modId = value;
            return this;
        }

        /** @return this builder. */
        public Builder loopRef(String value) {
            this.loopRef = value;
            return this;
        }

        /** @return this builder. */
        public Builder restart(RestartPolicy value) {
            this.restart = value;
            return this;
        }

        /** @return this builder. */
        public Builder orphan(OrphanPolicy value) {
            this.orphan = value;
            return this;
        }

        /** @return this builder. */
        public Builder origin(Origin value) {
            this.origin = value;
            return this;
        }

        /** @return this builder. */
        public Builder template(String value) {
            this.templateId = value;
            return this;
        }

        /** @return this builder. */
        public Builder templateVersion(String value) {
            this.templateVersion = value;
            return this;
        }

        /** @return this builder. */
        public Builder modelProfile(String value) {
            this.modelProfileId = value;
            return this;
        }

        /** @return this builder. */
        public Builder meta(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /** @return the built manifest. */
        public AgentManifest build() {
            return new AgentManifest(name, modId, loopRef, restart, orphan, origin, templateId,
                    templateVersion, modelProfileId, metadata);
        }
    }
}
