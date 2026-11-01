package ch.predmet.jarness.core.approval;

import ch.predmet.jarness.api.approval.ApprovalDecision;
import ch.predmet.jarness.api.approval.ApprovalHandler;
import ch.predmet.jarness.api.approval.ApprovalRegistry;
import ch.predmet.jarness.api.approval.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link ApprovalRegistry}: one active handler, last registration wins.
 */
public final class ApprovalRegistryImpl implements ApprovalRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalRegistryImpl.class);

    private final Map<String, ApprovalHandler> handlers = new LinkedHashMap<>();
    private volatile String active;

    @Override
    public synchronized void register(String name, ApprovalHandler handler) {
        if (name == null || name.isBlank() || handler == null) {
            return;
        }
        handlers.put(name, handler);
        active = name;
        LOG.info("approval handler registered: {}", name);
    }

    @Override
    public synchronized boolean unregister(String name) {
        boolean removed = handlers.remove(name) != null;
        if (removed && name.equals(active)) {
            active = handlers.isEmpty() ? null : handlers.keySet().iterator().next();
        }
        return removed;
    }

    @Override
    public synchronized Optional<ApprovalHandler> handler() {
        return active == null ? Optional.empty() : Optional.ofNullable(handlers.get(active));
    }

    @Override
    public ApprovalDecision ask(ApprovalRequest request, Duration timeout) {
        ApprovalHandler handler = handler().orElse(null);
        if (handler == null) {
            LOG.warn("approval required for {} but no handler is registered; denying", request.tool());
            return ApprovalDecision.DENY;
        }
        try {
            ApprovalDecision decision = handler.approve(request, timeout == null ? Duration.ofMinutes(5) : timeout);
            return decision == null ? ApprovalDecision.DENY : decision;
        } catch (Throwable t) {
            LOG.warn("approval handler failed for {}; denying", request.tool(), t);
            return ApprovalDecision.DENY;
        }
    }
}
