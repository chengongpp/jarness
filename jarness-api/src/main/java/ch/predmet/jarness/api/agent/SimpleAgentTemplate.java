package ch.predmet.jarness.api.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plain template implementation, usable by mods and by agents that author templates at runtime.
 *
 * @param id           template id
 * @param version      template version
 * @param name         display name
 * @param description  one-liner
 * @param systemPrompt system prompt preamble
 * @param toolNames    exposed tools
 * @param maxSteps     step cap, {@code -1} for the runtime default
 * @param temperature  temperature override, or {@code null}
 * @param dynamicMods  whether the template may load mods at runtime
 * @param metadata     extension metadata
 */
public record SimpleAgentTemplate(
        String id,
        String version,
        String name,
        String description,
        String systemPrompt,
        List<String> toolNames,
        int maxSteps,
        Double temperature,
        boolean dynamicMods,
        Map<String, Object> metadata) implements AgentTemplate {

    public SimpleAgentTemplate {
        id = id == null || id.isBlank() ? "tpl-" + UUID.randomUUID().toString().substring(0, 8) : id;
        version = version == null || version.isBlank() ? "1" : version.trim();
        name = name == null || name.isBlank() ? id : name;
        description = description == null ? "" : description;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Start building a template.
     *
     * @param id template id
     * @return a builder
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** @return a copy carrying a different system prompt. */
    public SimpleAgentTemplate withSystemPrompt(String prompt) {
        return new SimpleAgentTemplate(id, version, name, description, prompt, toolNames, maxSteps, temperature,
                dynamicMods, metadata);
    }

    /** @return a copy exposing exactly {@code tools}. */
    public SimpleAgentTemplate withTools(List<String> tools) {
        return new SimpleAgentTemplate(id, version, name, description, systemPrompt, tools, maxSteps, temperature,
                dynamicMods, metadata);
    }

    /** @return a copy carrying a different version. */
    public SimpleAgentTemplate withVersion(String newVersion) {
        return new SimpleAgentTemplate(id, newVersion, name, description, systemPrompt, toolNames, maxSteps,
                temperature, dynamicMods, metadata);
    }

    /** Fluent builder for {@link SimpleAgentTemplate}. */
    public static final class Builder {

        private final String id;
        private String version = "1";
        private String name;
        private String description = "";
        private String systemPrompt = "";
        private final List<String> toolNames = new java.util.ArrayList<>();
        private int maxSteps = -1;
        private Double temperature;
        private boolean dynamicMods;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
            this.name = id;
        }

        /** @return this builder. */
        public Builder version(String value) {
            this.version = value;
            return this;
        }

        /** @return this builder. */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** @return this builder. */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /** @return this builder. */
        public Builder systemPrompt(String value) {
            this.systemPrompt = value;
            return this;
        }

        /** @return this builder. */
        public Builder tools(String... names) {
            this.toolNames.clear();
            this.toolNames.addAll(List.of(names));
            return this;
        }

        /** @return this builder. */
        public Builder tools(List<String> names) {
            this.toolNames.clear();
            this.toolNames.addAll(names);
            return this;
        }

        /** @return this builder. */
        public Builder maxSteps(int value) {
            this.maxSteps = value;
            return this;
        }

        /** @return this builder. */
        public Builder temperature(Double value) {
            this.temperature = value;
            return this;
        }

        /** @return this builder. */
        public Builder dynamicMods(boolean value) {
            this.dynamicMods = value;
            return this;
        }

        /** @return this builder. */
        public Builder meta(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /** @return the built template. */
        public SimpleAgentTemplate build() {
            return new SimpleAgentTemplate(id, version, name, description, systemPrompt, toolNames, maxSteps,
                    temperature, dynamicMods, metadata);
        }
    }
}
