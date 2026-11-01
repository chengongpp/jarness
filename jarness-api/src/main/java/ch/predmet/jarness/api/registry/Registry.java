package ch.predmet.jarness.api.registry;

import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.exec.ExecutionProvider;
import ch.predmet.jarness.api.loop.LoopHook;
import ch.predmet.jarness.api.provider.Provider;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the runtime's extension points, versioned so an agent turn can pin one
 * snapshot.
 *
 * <p>Dynamic loading appends to the live registries and bumps the snapshot version; a turn that is
 * already running keeps the snapshot it started with, and the next turn reads the new one.</p>
 */
public interface Registry {

    /** @return the current snapshot version. */
    int version();

    /** @return an immutable snapshot of the extension points. */
    Snapshot snapshot();

    /** Bump the snapshot version after a dynamic change. */
    void bump();

    /**
     * One pinned view of the extension points.
     *
     * @param version            snapshot version
     * @param tools              tools by name
     * @param templates          templates by id
     * @param providers          model providers
     * @param executionProviders execution providers
     * @param loopHooks          loop hooks
     * @param sessionStores      session stores by namespace id
     */
    record Snapshot(int version, Map<String, Tool> tools, Map<String, AgentTemplate> templates,
                    List<Provider> providers, List<ExecutionProvider> executionProviders,
                    List<LoopHook> loopHooks, Map<String, SessionStore> sessionStores) {

        public Snapshot {
            tools = tools == null ? Map.of() : Map.copyOf(tools);
            templates = templates == null ? Map.of() : Map.copyOf(templates);
            providers = providers == null ? List.of() : List.copyOf(providers);
            executionProviders = executionProviders == null ? List.of() : List.copyOf(executionProviders);
            loopHooks = loopHooks == null ? List.of() : List.copyOf(loopHooks);
            sessionStores = sessionStores == null ? Map.of() : Map.copyOf(sessionStores);
        }

        /** @return the tool with this name, if present in this snapshot. */
        public Tool tool(String name) {
            return tools.get(name);
        }
    }
}
