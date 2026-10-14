package ch.predmet.jarness.api.agent;

import java.util.List;
import java.util.Optional;

/**
 * Registry of the templates agents can be created from.
 *
 * <p>Writable at runtime on purpose: the Full template's dynamic scenario is precisely an agent
 * registering a new template and then spawning a bot from it.</p>
 */
public interface AgentTemplateRegistry {

    /**
     * Register or replace a template.
     *
     * @param template the template to register
     */
    void register(AgentTemplate template);

    /**
     * Remove a template.
     *
     * @param id template id
     * @return whether a template was removed
     */
    boolean unregister(String id);

    /** @return the template with this id, if registered. */
    Optional<AgentTemplate> find(String id);

    /**
     * @param id template id
     * @return the template
     * @throws IllegalArgumentException when no such template is registered
     */
    AgentTemplate require(String id);

    /** @return every registered template, ordered by id. */
    List<AgentTemplate> all();

    /** @return every registered template id. */
    List<String> ids();

    /** @return whether a template with this id exists. */
    default boolean contains(String id) {
        return find(id).isPresent();
    }
}
