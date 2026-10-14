package ch.predmet.jarness.api.hook;

import java.util.List;
import java.util.function.Consumer;

/**
 * Ordered lifecycle dispatch, shared by the whole runtime.
 *
 * <p>One bus serves every agent, so hooks are global by default; an agent-scoped hook is expressed by
 * checking the payload's agent inside the hook body.</p>
 */
public interface HookBus {

    /**
     * Attach a hook.
     *
     * @param point    the point to listen to
     * @param name     stable name; when {@code null} a generated name is used
     * @param priority order within the point, lower runs earlier
     * @param handler  the callback
     * @return a registration whose {@code close()} detaches the hook
     */
    HookRegistration on(HookPoint point, String name, int priority, Consumer<HookContext> handler);

    /** Attach a hook with default name and priority. */
    default HookRegistration on(HookPoint point, Consumer<HookContext> handler) {
        return on(point, null, 1000, handler);
    }

    /**
     * Attach an already-built hook.
     *
     * @param hook the hook to attach
     * @return a registration whose {@code close()} detaches the hook
     */
    HookRegistration register(Hook hook);

    /**
     * Dispatch one payload to every hook of a point, in order.
     *
     * @param point   the point being dispatched
     * @param payload the event object
     * @return the context after dispatch, for the caller to inspect cancellation
     */
    HookContext emit(HookPoint point, Object payload);

    /** @return the hooks of one point in execution order. */
    List<Hook> hooks(HookPoint point);

    /** @return the number of attached hooks across all points. */
    int size();

    /** @return whether no hook is attached to this point. */
    default boolean isEmpty(HookPoint point) {
        return hooks(point).isEmpty();
    }
}
