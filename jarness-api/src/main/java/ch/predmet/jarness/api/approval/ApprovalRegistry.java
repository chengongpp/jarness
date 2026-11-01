package ch.predmet.jarness.api.approval;

import java.time.Duration;
import java.util.Optional;

/**
 * The runtime's approval handler table. Hosts (a TUI, a web console) register a handler; libraries
 * without one simply deny every {@link ApprovalPolicy#ASK} call.
 */
public interface ApprovalRegistry {

    /**
     * Register a handler, replacing any previous one.
     *
     * @param name    handler name
     * @param handler the handler
     */
    void register(String name, ApprovalHandler handler);

    /**
     * Remove a handler.
     *
     * @param name handler name
     * @return whether a handler was removed
     */
    boolean unregister(String name);

    /** @return the active handler, if any. */
    Optional<ApprovalHandler> handler();

    /**
     * Ask the active handler.
     *
     * @param request the call
     * @param timeout maximum wait
     * @return the decision; {@link ApprovalDecision#DENY} when no handler exists or it fails
     */
    ApprovalDecision ask(ApprovalRequest request, Duration timeout);
}
