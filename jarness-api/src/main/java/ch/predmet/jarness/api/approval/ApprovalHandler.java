package ch.predmet.jarness.api.approval;

import java.time.Duration;

/** Host hook for {@link ApprovalPolicy#ASK}. */
@FunctionalInterface
public interface ApprovalHandler {

    /**
     * Decide one call. Implementations may block; the runtime calls this on the agent's virtual
     * thread. Returning {@link ApprovalDecision#DENY} is always safe.
     *
     * @param request the call
     * @param timeout how long the runtime is willing to wait
     * @return the decision
     */
    ApprovalDecision approve(ApprovalRequest request, Duration timeout);
}
