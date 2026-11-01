package ch.predmet.jarness.api.agent;

import java.util.List;
import java.util.Map;

/**
 * A reusable agent definition: the system prompt, the tool set, and the loop it runs.
 *
 * <p>Jarness ships Void, Minimal, Standard, and Full. Nothing about the four is privileged: they are
 * templates registered like any other, so a mod can replace, extend, or wrap them, and the Full
 * template is what lets a running agent author a new template for a sub-bot.</p>
 *
 * <p>A template deliberately carries no model: the model is a property of the namespace (or a
 * spawn-time manifest override), never of the executable definition. Keeping the two apart is what
 * lets the same template run on a small local model in one namespace and a large remote one in
 * another.</p>
 */
public interface AgentTemplate {

    /** @return stable template id, e.g. {@code standard}. */
    String id();

    /** @return template version; the image records it so restore can verify compatibility. */
    default String version() {
        return "1";
    }

    /** @return human readable name. */
    String name();

    /** @return one-liner shown in template listings. */
    String description();

    /** @return the system prompt preamble; the runtime appends tool guidance to it. */
    String systemPrompt();

    /** @return the names of the tools this template exposes, in prompt order. */
    List<String> toolNames();

    /** @return step cap for one turn, or {@code -1} to use the runtime default. */
    default int maxSteps() {
        return -1;
    }

    /** @return sampling temperature override, or {@code null} for the model profile's default. */
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
