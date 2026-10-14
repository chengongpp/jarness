package ch.predmet.jarness.api.agent;

import ch.predmet.jarness.api.provider.ModelRef;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to create one agent.
 *
 * <p>Either {@code templateId} names a registered template, or {@code template} supplies one inline;
 * the inline form is what a Full agent uses to spin up a purpose-built bot.</p>
 *
 * @param templateId    registered template id, or {@code null} when {@code template} is supplied
 * @param template      inline template, or {@code null} when {@code templateId} is used
 * @param name          agent name, or {@code null} to derive one
 * @param workspace     working directory, or {@code null} for a fresh directory under the workspace root
 * @param sessionId     session to resume, or {@code null} for a new session
 * @param systemPrompt  system prompt override, or {@code null}
 * @param toolNames     tool override; empty means "as the template says"
 * @param model         model override, or {@code null}
 * @param metadata      extension metadata
 */
public record AgentRequest(
        String templateId,
        AgentTemplate template,
        String name,
        Path workspace,
        String sessionId,
        String systemPrompt,
        List<String> toolNames,
        ModelRef model,
        Map<String, Object> metadata) {

    public AgentRequest {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (template == null && (templateId == null || templateId.isBlank())) {
            throw new IllegalArgumentException("AgentRequest needs either a template id or an inline template");
        }
    }

    /** @return a builder for an agent created from a registered template. */
    public static Builder builder(String templateId) {
        return new Builder(templateId);
    }

    /** @return a builder for an agent created from an inline template. */
    public static Builder builder(AgentTemplate template) {
        return new Builder(template.id()).template(template);
    }

    /** Fluent builder for {@link AgentRequest}. */
    public static final class Builder {

        private String templateId;
        private AgentTemplate template;
        private String name;
        private Path workspace;
        private String sessionId;
        private String systemPrompt;
        private List<String> toolNames = List.of();
        private ModelRef model;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String templateId) {
            this.templateId = templateId;
        }

        /** @return this builder. */
        public Builder template(AgentTemplate value) {
            this.template = value;
            this.templateId = value == null ? templateId : value.id();
            return this;
        }

        /** @return this builder. */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** @return this builder. */
        public Builder workspace(Path value) {
            this.workspace = value;
            return this;
        }

        /** @return this builder. */
        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        /** @return this builder. */
        public Builder systemPrompt(String value) {
            this.systemPrompt = value;
            return this;
        }

        /** @return this builder. */
        public Builder tools(List<String> value) {
            this.toolNames = value == null ? List.of() : List.copyOf(value);
            return this;
        }

        /** @return this builder. */
        public Builder tools(String... value) {
            this.toolNames = List.of(value);
            return this;
        }

        /** @return this builder. */
        public Builder model(ModelRef value) {
            this.model = value;
            return this;
        }

        /** @return this builder. */
        public Builder model(String providerId, String modelId) {
            this.model = new ModelRef(providerId, modelId);
            return this;
        }

        /** @return this builder. */
        public Builder meta(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /** @return the built request. */
        public AgentRequest build() {
            return new AgentRequest(templateId, template, name, workspace, sessionId, systemPrompt, toolNames,
                    model, metadata);
        }
    }
}
