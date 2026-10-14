package ch.predmet.jarness.api.agent;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.hook.HookContext;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.HookRegistration;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Per-agent extension surface.
 *
 * <p>Where {@link ch.predmet.jarness.api.instrument.InstrumentContext} changes behavior for every
 * agent, this context changes behavior for exactly one: prompt sections, tool visibility, scoped
 * hooks, and a scratch map for mods that need to keep state about the agent.</p>
 */
public interface AgentContext {

    /** @return the agent this context belongs to. */
    Agent agent();

    /** @return the running runtime. */
    JarnessRuntime runtime();

    /** @return a logger tagged with the agent id. */
    Logger log();

    /**
     * Contribute an ordered system-prompt section.
     *
     * @param name  stable section name; registering the same name twice replaces it
     * @param order sort order among sections; lower appears earlier
     * @param text  the text, resolved on every prompt assembly so it can follow live state
     */
    void systemPromptSection(String name, int order, Supplier<String> text);

    /**
     * Contribute ordered dynamic context re-stated near the end of every request.
     *
     * @param name  stable name
     * @param order sort order
     * @param text  the text, resolved per assembly
     */
    void userContext(String name, int order, Supplier<String> text);

    /**
     * Replace the visible tool set with exactly {@code names}.
     *
     * @param names tool names; unknown names are ignored with a warning
     */
    void restrictTools(Collection<String> names);

    /**
     * Add one tool to the visible set.
     *
     * @param name tool name
     */
    void allowTool(String name);

    /**
     * Remove one tool from the visible set.
     *
     * @param name tool name
     */
    void denyTool(String name);

    /** @return the currently visible tool names, in prompt order. */
    List<String> visibleToolNames();

    /**
     * Attach a hook scoped to this agent.
     *
     * @param point    the point to listen to
     * @param name     stable name, or {@code null} for a generated one
     * @param priority order within the point
     * @param handler  the callback
     * @return a registration whose {@code close()} detaches the hook
     */
    HookRegistration on(HookPoint point, String name, int priority, Consumer<HookContext> handler);

    /**
     * Store a value for the lifetime of the agent.
     *
     * @param key   key
     * @param value value, or {@code null} to remove
     */
    void put(String key, Object value);

    /**
     * Read a stored value.
     *
     * @param key key
     * @return the value, if present
     */
    Optional<Object> get(String key);
}
