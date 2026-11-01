package ch.predmet.jarness.api.exec;

import ch.predmet.jarness.api.capability.CapabilitySet;

import java.util.List;
import java.util.Optional;

/**
 * The runtime's execution routing table.
 *
 * <p>Tools are declarative, executors are physical. The registry picks a live provider for a tool
 * and a capability view; when none matches, the call fails with {@link ExecutionException} instead
 * of silently running in the daemon.</p>
 */
public interface ExecutionRegistry {

    /**
     * Register a provider, replacing one with the same id.
     *
     * @param provider the provider
     */
    void register(ExecutionProvider provider);

    /**
     * Remove a provider.
     *
     * @param id provider id
     * @return whether a provider was removed
     */
    boolean unregister(String id);

    /** @return every registered provider, in registration order. */
    List<ExecutionProvider> providers();

    /**
     * Select a live provider for a tool and capability view.
     *
     * @param tool  executor tool name
     * @param caps  capabilities the call holds
     * @return the provider, or empty
     */
    Optional<ExecutionProvider> select(String tool, CapabilitySet caps);

    /**
     * Invoke a call through the best provider.
     *
     * @param request the call
     * @return the result
     * @throws ExecutionException when no provider can run it
     */
    ExecResult invoke(ExecRequest request);

    /** @return whether at least one provider is alive. */
    default boolean healthy() {
        return providers().stream().anyMatch(ExecutionProvider::alive);
    }
}
