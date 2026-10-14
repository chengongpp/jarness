package ch.predmet.jarness.core.tool;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link ToolRegistry}: a synchronized insertion-ordered map keyed by tool name.
 *
 * <p>Registering a tool under an existing name replaces the previous tool in place.
 * {@link #resolve(Collection)} skips unknown names with a warning so a stale template or mod never
 * breaks an agent's tool set.</p>
 */
public final class ToolRegistryImpl implements ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistryImpl.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Create an empty registry. */
    public ToolRegistryImpl() {
    }

    @Override
    public synchronized void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        Tool previous = tools.put(tool.name(), tool);
        if (previous != null && previous != tool) {
            LOG.info("replaced tool '{}'", tool.name());
        }
    }

    @Override
    public synchronized boolean unregister(String name) {
        return tools.remove(name) != null;
    }

    @Override
    public synchronized Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Tool require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("no tool registered as '" + name + "'"));
    }

    @Override
    public List<Tool> resolve(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<Tool> resolved = new ArrayList<>(names.size());
        for (String name : names) {
            Optional<Tool> tool = find(name);
            if (tool.isPresent()) {
                resolved.add(tool.get());
            } else {
                LOG.warn("skipping unknown tool '{}'", name);
            }
        }
        return resolved;
    }

    @Override
    public synchronized List<Tool> all() {
        return tools.values().stream().sorted(Comparator.comparing(Tool::name)).toList();
    }

    @Override
    public synchronized Set<String> names() {
        return Set.copyOf(new LinkedHashSet<>(tools.keySet()));
    }
}
