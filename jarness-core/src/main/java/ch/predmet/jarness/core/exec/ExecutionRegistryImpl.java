package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.exec.ExecutionException;
import ch.predmet.jarness.api.exec.ExecutionProvider;
import ch.predmet.jarness.api.exec.ExecutionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link ExecutionRegistry}: an ordered provider table with first-match routing.
 */
public final class ExecutionRegistryImpl implements ExecutionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionRegistryImpl.class);

    private final Map<String, ExecutionProvider> providers = new LinkedHashMap<>();

    @Override
    public synchronized void register(ExecutionProvider provider) {
        providers.put(provider.id(), provider);
        LOG.info("execution provider {} ({}): tools={} alive={}", provider.id(), provider.kind(),
                provider.tools(), provider.alive());
    }

    @Override
    public synchronized boolean unregister(String id) {
        ExecutionProvider removed = providers.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            removed.close();
        } catch (RuntimeException e) {
            LOG.warn("execution provider {} failed to close", id, e);
        }
        return true;
    }

    @Override
    public synchronized List<ExecutionProvider> providers() {
        return List.copyOf(new ArrayList<>(providers.values()));
    }

    @Override
    public synchronized Optional<ExecutionProvider> select(String tool, CapabilitySet caps) {
        for (ExecutionProvider provider : providers.values()) {
            if (provider.alive() && provider.tools().contains(tool)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    @Override
    public ExecResult invoke(ExecRequest request) {
        ExecutionProvider provider = select(request.tool(), request.capabilities())
                .orElseThrow(() -> new ExecutionException(ExecutionException.Reason.NO_PROVIDER,
                        "no live execution provider advertises tool \"" + request.tool() + "\""));
        try {
            return provider.invoke(request);
        } catch (ExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExecutionException(ExecutionException.Reason.PROVIDER_DEAD,
                    "provider " + provider.id() + " failed: " + e.getMessage());
        }
    }

    /** Close every provider. */
    public synchronized void shutdown() {
        for (ExecutionProvider provider : providers.values()) {
            try {
                provider.close();
            } catch (RuntimeException e) {
                LOG.warn("execution provider {} failed to close", provider.id(), e);
            }
        }
        providers.clear();
    }
}
