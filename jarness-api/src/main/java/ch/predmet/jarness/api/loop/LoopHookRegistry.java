package ch.predmet.jarness.api.loop;

import java.util.List;
import java.util.Optional;

/** Registry of {@link LoopHook}s. */
public interface LoopHookRegistry {

    /**
     * Register or replace a hook by name.
     *
     * @param hook the hook
     */
    void register(LoopHook hook);

    /**
     * Remove a hook.
     *
     * @param name hook name
     * @return whether a hook was removed
     */
    boolean unregister(String name);

    /** @return hooks in apply order. */
    List<LoopHook> all();

    /** @return the hook with this name. */
    Optional<LoopHook> byName(String name);
}
