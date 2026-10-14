package ch.predmet.jarness.api.hook;

import ch.predmet.jarness.api.JarnessRuntime;

/**
 * Context handed to one hook invocation.
 *
 * <p>The payload is deliberately untyped in the interface and narrowed with {@link #payload(Class)}
 * at the call site: hook payloads are mutable event objects, and a hook that rewrites them is doing
 * exactly what the runtime expects.</p>
 */
public interface HookContext {

    /** @return the point being dispatched. */
    HookPoint point();

    /** @return the event payload; never {@code null}. */
    Object payload();

    /**
     * Narrow the payload.
     *
     * @param type the expected payload type
     * @param <T>  payload type
     * @return the payload cast to {@code type}
     * @throws IllegalStateException when the payload is of another type
     */
    default <T> T payload(Class<T> type) {
        Object payload = payload();
        if (!type.isInstance(payload)) {
            throw new IllegalStateException("hook " + point() + " payload is "
                    + payload.getClass().getName() + ", not " + type.getName());
        }
        return type.cast(payload);
    }

    /** @return the running runtime. */
    JarnessRuntime runtime();

    /**
     * Cancel the operation guarded by this point.
     *
     * @param reason short human-readable reason, surfaced to the agent as a refusal
     */
    void cancel(String reason);

    /** @return whether the operation was cancelled; the first cancel reason wins. */
    boolean cancelled();

    /** @return the cancellation reason, or an empty string when not cancelled. */
    String cancelReason();

    /** Prevent later hooks of the same point from running. */
    void stopPropagation();

    /** @return whether propagation was stopped. */
    boolean propagationStopped();
}
