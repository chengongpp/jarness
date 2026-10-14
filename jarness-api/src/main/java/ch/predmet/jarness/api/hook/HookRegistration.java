package ch.predmet.jarness.api.hook;

/**
 * Handle of one hook registration; closing it removes the hook.
 *
 * <p>Dynamically loaded mods can therefore attach hooks for a limited time, and shutting down a
 * runtime can detach everything a mod installed.</p>
 */
public interface HookRegistration extends AutoCloseable {

    /** @return the registered hook. */
    Hook hook();

    @Override
    void close();
}
