package ch.predmet.jarness.api.tool;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The runtime's tool table.
 *
 * <p>Tools are registered by instruments and looked up per agent, so the same registry serves agents
 * with different visible tool sets.</p>
 */
public interface ToolRegistry {

    /**
     * Register or replace a tool.
     *
     * @param tool the tool to register
     */
    void register(Tool tool);

    /**
     * Remove a tool.
     *
     * @param name tool name
     * @return whether a tool was removed
     */
    boolean unregister(String name);

    /** @return the tool with this exact name, if registered. */
    Optional<Tool> find(String name);

    /**
     * @param name tool name
     * @return the tool
     * @throws IllegalArgumentException when no such tool is registered
     */
    Tool require(String name);

    /**
     * Resolve a list of names, skipping unknown ones.
     *
     * @param names tool names
     * @return the resolved tools, in the requested order
     */
    List<Tool> resolve(Collection<String> names);

    /** @return every registered tool, ordered by name. */
    List<Tool> all();

    /** @return every registered tool name. */
    Set<String> names();

    /** @return whether a tool with this name exists. */
    default boolean contains(String name) {
        return find(name).isPresent();
    }
}
