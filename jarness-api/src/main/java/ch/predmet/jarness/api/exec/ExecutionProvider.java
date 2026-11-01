package ch.predmet.jarness.api.exec;

import ch.predmet.jarness.api.capability.CapKind;

import java.util.Set;

/**
 * A backend that can execute tools outside the agent loop.
 *
 * <p>The provider is a bridge: it owns the connection to an executor and translates an
 * {@link ExecRequest} into a result. Capability enforcement happens in the executor, using the view
 * carried by the request.</p>
 */
public interface ExecutionProvider extends AutoCloseable {

    /** @return stable provider id. */
    String id();

    /** @return local or remote. */
    ExecProviderKind kind();

    /** @return executor tool names advertised by this provider. */
    Set<String> tools();

    /** @return capability kinds this executor can enforce. */
    Set<CapKind> capabilities();

    /** @return whether the executor is connected and usable. */
    boolean alive();

    /**
     * Invoke one call.
     *
     * @param request the call
     * @return the result
     * @throws ExecutionException when the provider cannot produce a result
     */
    ExecResult invoke(ExecRequest request);

    /**
     * Ask the executor to cancel an in-flight call.
     *
     * @param callId call id
     * @param reason short reason
     */
    void cancel(String callId, String reason);

    @Override
    default void close() {
    }
}
