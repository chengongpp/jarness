package ch.predmet.jarness.api.agent;

import ch.predmet.jarness.api.provider.ModelRef;

import java.util.List;
import java.util.Map;

/**
 * A reusable agent definition: the system prompt, the tool set, and the model policy.
 *
 * <p>Jarness ships Void, Minimal, Standard, and Full. Nothing about the four is privileged: they are
 * templates registered like any other, so a mod can replace, extend, or wrap them, and the Full
 * template is what lets a running agent author a new template for a sub-bot.</p>
 */
public interface AgentTemplate {

    /** @return stable template id, e.g. {@code standard}. */
    String id();

    /** @return human readable name. */
    String name();

    /** @return one-liner shown in template listings. */
    String description();

    /** @return the system prompt preamble; the runtime appends tool guidance to it. */
    String systemPrompt();

    /** @return the names of the tools this template exposes, in prompt order. */
    List<String> toolNames();

    /** @return a model this template insists on, or {@code null} to use the runtime default. */
    default ModelRef model() {
        return null;
    }

    /** @return step cap for one turn, or {@code -1} to use the runtime default. */
    default int maxSteps() {
        return -1;
    }

    /** @return sampling temperature override, or {@code null} for the runtime default. */
    default Double temperature() {
        return null;
    }

    /**
     * @return whether agents of this template may load mods into the running runtime; only Full is
     *         expected to answer {@code true}
     */
    default boolean dynamicMods() {
        return false;
    }

    /** @return extension metadata. */
    default Map<String, Object> metadata() {
        return Map.of();
    }
}
