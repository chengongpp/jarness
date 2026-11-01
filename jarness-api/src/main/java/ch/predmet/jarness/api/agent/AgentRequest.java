package ch.predmet.jarness.api.agent;

import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.provider.ModelRef;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ergonomic creation input for one agent.
 *
 * <p>This is the convenience form used by the CLI, the TUI, and the agent-spawning tools. The
 * supervisor immediately turns it into an {@link ch.predmet.jarness.api.kernel.AgentManifest} plus
 * spawn parameters; code that needs restart/orphan/loop policy or an explicit parent should call
 * {@link ch.predmet.jarness.api.supervisor.Supervisor#spawn(long, String,
 * ch.predmet.jarness.api.kernel.AgentManifest, CapabilitySet, Budget)} directly.</p>
 *
 * <p>Either {@code templateId} names a registered template, or {@code template} supplies one inline.</p>
 *
 * @param templateId    registered template id, or {@code null} when {@code template} is supplied
 * @param template      inline template, or {@code null} when {@code templateId} is used
 * @param name          agent name, or {@code null} to derive one
 * @param workspace     working directory, or {@code null} for a fresh directory under the workspace root
 * @param sessionId     session to resume, or {@code null} for a new session
 * @param systemPrompt  system prompt override, or {@code null}
 * @param toolNames     tool override; empty means "as the template says"
 * @param model         model override, or {@code null} to use the namespace's model profile
 * @param parentPid     parent pid, {@code 0} for a direct child of the supervisor root
 * @param namespaceId   namespace id, or {@code null} to use the parent's namespace / {@code default}
 * @param capabilities  requested capabilities, or {@code null} to inherit from the parent
 * @param budget        requested budget, or {@code null} for the child default
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
        long parentPid,
        String namespaceId,
        CapabilitySet capabilities,
        Budget budget,
        Map<String, Object> metadata) {

    public AgentRequest {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (template == null && (templateId == null || templateId.isBlank())) {
            throw new IllegalArgumentException("AgentRequest needs either a template id or an inline template");
        }
        parentPid = Math.max(0L, parentPid);
        namespaceId = namespaceId == null || namespaceId.isBlank() ? null : namespaceId.trim();
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
        private long parentPid;
        private String namespaceId;
        private CapabilitySet capabilities;
        private Budget budget;
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
        public Builder parent(long pid) {
            this.parentPid = Math.max(0L, pid);
            return this;
        }

        /** @return this builder. */
        public Builder namespace(String value) {
            this.namespaceId = value;
            return this;
        }

        /** @return this builder. */
        public Builder capabilities(CapabilitySet value) {
            this.capabilities = value;
            return this;
        }

        /** @return this builder. */
        public Builder budget(Budget value) {
            this.budget = value;
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
                    model, parentPid, namespaceId, capabilities, budget, metadata);
        }
    }
}
