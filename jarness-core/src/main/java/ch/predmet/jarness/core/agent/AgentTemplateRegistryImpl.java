package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Plain thread-safe {@link AgentTemplateRegistry}. */
public final class AgentTemplateRegistryImpl implements AgentTemplateRegistry {

    private final Map<String, AgentTemplate> templates = new LinkedHashMap<>();

    @Override
    public synchronized void register(AgentTemplate template) {
        templates.put(template.id(), template);
    }

    @Override
    public synchronized boolean unregister(String id) {
        return templates.remove(id) != null;
    }

    @Override
    public synchronized Optional<AgentTemplate> find(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    @Override
    public AgentTemplate require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("no agent template \"" + id
                + "\" registered; available: " + ids()));
    }

    @Override
    public synchronized List<AgentTemplate> all() {
        return templates.values().stream().sorted(Comparator.comparing(AgentTemplate::id)).toList();
    }

    @Override
    public synchronized List<String> ids() {
        return all().stream().map(AgentTemplate::id).toList();
    }
}
