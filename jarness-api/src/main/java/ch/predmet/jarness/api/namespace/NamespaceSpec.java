package ch.predmet.jarness.api.namespace;

import ch.predmet.jarness.api.session.SessionStore;

import java.util.List;
import java.util.Objects;

/**
 * Creation input for a namespace.
 *
 * @param id                     namespace id
 * @param metadata               metadata, model tier, policies, and capability ceiling
 * @param store                  the store that will hold this namespace's images
 * @param defaultTemplateId      default agent template, or {@code null} to require an explicit one
 * @param defaultTemplateVersion required template version, or {@code null}/blank for "any"
 * @param executionProviders     ordered provider ids or {@code local}/{@code remote} kind selectors;
 *                               empty means no routing restriction
 */
public record NamespaceSpec(String id, NamespaceMetadata metadata, SessionStore store,
                            String defaultTemplateId, String defaultTemplateVersion,
                            List<String> executionProviders) {

    /** Compatibility constructor for a namespace with unrestricted execution routing. */
    public NamespaceSpec(String id, NamespaceMetadata metadata, SessionStore store,
                         String defaultTemplateId, String defaultTemplateVersion) {
        this(id, metadata, store, defaultTemplateId, defaultTemplateVersion, List.of());
    }

    public NamespaceSpec {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("namespace id must not be blank");
        }
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(store, "store");
        defaultTemplateId = defaultTemplateId == null || defaultTemplateId.isBlank()
                ? null : defaultTemplateId.trim();
        defaultTemplateVersion = defaultTemplateVersion == null ? "" : defaultTemplateVersion.trim();
        executionProviders = executionProviders == null ? List.of() : executionProviders.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** @return a spec with metadata and a store, no pinned template. */
    public static NamespaceSpec of(String id, NamespaceMetadata metadata, SessionStore store) {
        return new NamespaceSpec(id, metadata, store, null, "", List.of());
    }
}
