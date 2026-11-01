package ch.predmet.jarness.core.namespace;

import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceMetadata;
import ch.predmet.jarness.api.namespace.NamespaceSpec;
import ch.predmet.jarness.api.session.SessionStore;

import java.util.Objects;

/**
 * Immutable {@link Namespace} created from a {@link NamespaceSpec}.
 */
public final class NamespaceImpl implements Namespace {

    private final String id;
    private final NamespaceMetadata metadata;
    private final SessionStore store;
    private final String defaultTemplateId;
    private final String defaultTemplateVersion;
    private final boolean isDefault;

    /**
     * @param id        namespace id
     * @param spec      creation input
     * @param isDefault whether this is the default namespace
     */
    public NamespaceImpl(String id, NamespaceSpec spec, boolean isDefault) {
        this.id = Objects.requireNonNull(id, "id");
        this.metadata = spec.metadata();
        this.store = spec.store();
        this.defaultTemplateId = spec.defaultTemplateId();
        this.defaultTemplateVersion = spec.defaultTemplateVersion();
        this.isDefault = isDefault;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public NamespaceMetadata metadata() {
        return metadata;
    }

    @Override
    public SessionStore store() {
        return store;
    }

    @Override
    public String defaultTemplateId() {
        return defaultTemplateId;
    }

    @Override
    public String defaultTemplateVersion() {
        return defaultTemplateVersion;
    }

    @Override
    public String toString() {
        return "Namespace[" + id + (isDefault ? ", default" : "") + "]";
    }
}
