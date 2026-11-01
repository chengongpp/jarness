package ch.predmet.jarness.core.registry;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.registry.Registry;
import ch.predmet.jarness.api.tool.Tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Default {@link Registry}: builds a snapshot lazily from the live registries. */
public final class RegistryImpl implements Registry {

    private final Supplier<JarnessRuntime> runtime;
    private final AtomicInteger version = new AtomicInteger(1);

    /**
     * @param runtime the runtime, looked up lazily
     */
    public RegistryImpl(Supplier<JarnessRuntime> runtime) {
        this.runtime = runtime;
    }

    @Override
    public int version() {
        return version.get();
    }

    @Override
    public Snapshot snapshot() {
        JarnessRuntime rt = runtime.get();
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (Tool tool : rt.tools().all()) {
            tools.put(tool.name(), tool);
        }
        Map<String, AgentTemplate> templates = new LinkedHashMap<>();
        for (AgentTemplate template : rt.templates().all()) {
            templates.put(template.id(), template);
        }
        Map<String, ch.predmet.jarness.api.session.SessionStore> stores = new LinkedHashMap<>();
        for (ch.predmet.jarness.api.namespace.Namespace namespace : rt.namespaces().list()) {
            stores.put(namespace.id(), namespace.store());
        }
        return new Snapshot(version.get(), tools, templates, rt.providers().all(),
                rt.execution().providers(), rt.loopHooks().all(), stores);
    }

    @Override
    public void bump() {
        version.incrementAndGet();
    }
}
