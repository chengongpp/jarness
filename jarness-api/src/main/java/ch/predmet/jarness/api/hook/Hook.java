package ch.predmet.jarness.api.hook;

import java.util.function.Consumer;

/**
 * One ordered lifecycle callback.
 *
 * <p>Hooks attached to the same point run in ascending {@link #priority()}; ties keep registration
 * order. A hook may cancel the current operation or stop propagation to later hooks.</p>
 */
public interface Hook {

    /** @return the point this hook listens to. */
    HookPoint point();

    /** @return a stable name used in diagnostics and for removal. */
    String name();

    /** @return execution order; lower runs earlier. */
    default int priority() {
        return 1000;
    }

    /**
     * Handle one event.
     *
     * @param ctx the event context, including the typed payload
     */
    void handle(HookContext ctx);

    /** @return a hook backed by a lambda. */
    static Hook of(HookPoint point, String name, int priority, Consumer<HookContext> body) {
        return new Hook() {
            @Override
            public HookPoint point() {
                return point;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public void handle(HookContext ctx) {
                body.accept(ctx);
            }
        };
    }
}
