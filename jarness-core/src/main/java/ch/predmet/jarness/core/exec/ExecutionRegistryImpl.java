package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.exec.ExecutionException;
import ch.predmet.jarness.api.exec.ExecutionProvider;
import ch.predmet.jarness.api.exec.ExecutionRegistry;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.JarnessRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default {@link ExecutionRegistry}: physical providers plus namespace-scoped routing.
 *
 * <p>A namespace names an ordered list of provider ids or {@code local}/{@code remote} kind
 * selectors. No route is inferred from agent location or silently changed after a failure.</p>
 */
public final class ExecutionRegistryImpl implements ExecutionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionRegistryImpl.class);

    private final Supplier<JarnessRuntime> runtime;
    private final Map<String, ExecutionProvider> providers = new LinkedHashMap<>();

    public ExecutionRegistryImpl(Supplier<JarnessRuntime> runtime) {
        this.runtime = runtime;
    }

    @Override
    public synchronized void register(ExecutionProvider provider) {
        ExecutionProvider replaced = providers.put(provider.id(), provider);
        LOG.info("execution provider {} ({}): tools={} alive={}", provider.id(), provider.kind(),
                provider.tools(), provider.alive());
        if (replaced != null && replaced != provider) {
            try {
                replaced.close();
            } catch (RuntimeException e) {
                LOG.warn("replaced execution provider {} failed to close", replaced.id(), e);
            }
        }
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
    public Optional<ExecutionProvider> select(ExecRequest request) {
        Namespace namespace = runtime.get().namespaces().find(request.namespace()).orElse(null);
        if (namespace == null) {
            return Optional.empty();
        }
        List<String> selectors = namespace.executionProviders().isEmpty()
                ? List.of("*") : namespace.executionProviders();
        for (String selector : selectors) {
            for (ExecutionProvider provider : providers()) {
                if (matches(selector, provider) && provider.alive()
                        && provider.tools().contains(request.tool())) {
                    return Optional.of(provider);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public ExecResult invoke(ExecRequest request) {
        ExecutionProvider provider = select(request).orElseThrow(() -> new ExecutionException(
                ExecutionException.Reason.NO_PROVIDER,
                "no live execution provider advertises tool \"" + request.tool() + "\" for namespace \""
                        + request.namespace() + "\" (route " + routeDescription(request.namespace()) + ")"));
        try {
            return provider.invoke(request);
        } catch (ExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExecutionException(ExecutionException.Reason.PROVIDER_DEAD,
                    "provider " + provider.id() + " failed: " + e.getMessage());
        }
    }

    private String routeDescription(String namespaceId) {
        return runtime.get().namespaces().find(namespaceId)
                .map(namespace -> namespace.executionProviders().isEmpty()
                        ? "*" : String.join(",", namespace.executionProviders()))
                .orElse("unknown namespace");
    }

    private static boolean matches(String selector, ExecutionProvider provider) {
        return "*".equals(selector) || provider.id().equals(selector)
                || provider.kind().name().equalsIgnoreCase(selector);
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
