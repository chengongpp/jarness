package ch.predmet.jarness.core.namespace;

import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceRegistry;
import ch.predmet.jarness.api.namespace.NamespaceSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link NamespaceRegistry}: an insertion-ordered map whose first entry is the default
 * namespace.
 *
 * <p>The registry deliberately knows nothing about agents; the supervisor is what refuses to remove
 * a namespace that still has live agents in it. That keeps creation/removal of environments
 * independent from the process table.</p>
 */
public final class NamespaceRegistryImpl implements NamespaceRegistry {

    private final Map<String, Namespace> namespaces = new LinkedHashMap<>();

    /**
     * @param defaultNamespace the namespace that always exists
     */
    public NamespaceRegistryImpl(Namespace defaultNamespace) {
        if (defaultNamespace == null || !defaultNamespace.isDefault()) {
            throw new IllegalArgumentException("the registry needs a default namespace");
        }
        namespaces.put(defaultNamespace.id(), defaultNamespace);
    }

    @Override
    public synchronized Namespace get(String id) {
        Namespace namespace = id == null ? null : namespaces.get(id);
        if (namespace == null) {
            throw new IllegalArgumentException("unknown namespace \"" + id + "\"; known: " + namespaces.keySet());
        }
        return namespace;
    }

    @Override
    public synchronized Optional<Namespace> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(namespaces.get(id));
    }

    @Override
    public synchronized Namespace defaultNs() {
        return namespaces.get(Namespace.DEFAULT_ID);
    }

    @Override
    public synchronized List<Namespace> list() {
        return List.copyOf(namespaces.values());
    }

    @Override
    public synchronized Namespace create(NamespaceSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (namespaces.containsKey(spec.id())) {
            throw new IllegalStateException("namespace \"" + spec.id() + "\" already exists");
        }
        Namespace namespace = new NamespaceImpl(spec.id(), spec, false);
        namespaces.put(spec.id(), namespace);
        return namespace;
    }

    @Override
    public synchronized boolean remove(String id) {
        Namespace namespace = id == null ? null : namespaces.get(id);
        if (namespace == null) {
            return false;
        }
        if (namespace.isDefault()) {
            throw new IllegalStateException("the default namespace cannot be removed");
        }
        namespaces.remove(id);
        return true;
    }

    /** @return the ids of every namespace, default first. */
    public synchronized List<String> ids() {
        return new ArrayList<>(namespaces.keySet());
    }
}
