package ch.predmet.jarness.api.namespace;

import java.util.List;
import java.util.Optional;

/**
 * The namespace table.
 *
 * <p>{@code default} always exists once the runtime starts and cannot be removed. Every other
 * namespace is created from configuration or by a mod through this registry; removing one requires
 * that no agent still lives in it.</p>
 */
public interface NamespaceRegistry {

    /**
     * @param id namespace id
     * @return the namespace
     * @throws IllegalArgumentException when no such namespace exists
     */
    Namespace get(String id);

    /**
     * @param id namespace id
     * @return the namespace, or empty
     */
    Optional<Namespace> find(String id);

    /** @return the default namespace. */
    Namespace defaultNs();

    /** @return every namespace, default first. */
    List<Namespace> list();

    /** @return whether a namespace with this id exists. */
    default boolean exists(String id) {
        return find(id).isPresent();
    }

    /**
     * Create a namespace.
     *
     * @param spec creation input
     * @return the created namespace
     * @throws IllegalStateException when the id is already taken or the default template is unknown
     */
    Namespace create(NamespaceSpec spec);

    /**
     * Remove a namespace.
     *
     * @param id namespace id
     * @return whether a namespace was removed
     * @throws IllegalStateException when the namespace is default or still holds agents
     */
    boolean remove(String id);
}
