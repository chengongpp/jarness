package ch.predmet.jarness.api;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;
import ch.predmet.jarness.api.config.JarnessConfig;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.hook.HookBus;
import ch.predmet.jarness.api.instrument.InstrumentRegistry;
import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.mod.ModLoader;
import ch.predmet.jarness.api.namespace.NamespaceRegistry;
import ch.predmet.jarness.api.provider.ProviderRegistry;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.supervisor.Supervisor;
import ch.predmet.jarness.api.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The runtime facade: one object that exposes every registry a host or mod may need.
 *
 * <p>Calling {@link #start()} bakes the mods and starts the supervisor; {@link #close()} stops the
 * supervisor first and releases the mod loader last, which mirrors the lifetime rule that the
 * supervisor is the first thing up and the last thing down.</p>
 */
public interface JarnessRuntime extends AutoCloseable {

    /** @return the runtime version string. */
    String version();

    /** @return the base directory layout. */
    JarnessLayout layout();

    /** @return resolved runtime configuration. */
    JarnessConfig config();

    /** @return the mod loader singleton. */
    ModLoader modLoader();

    /** @return the supervisor singleton. */
    Supervisor supervisor();

    /** @return the session store. */
    SessionStore sessions();

    /** @return the provider registry. */
    ProviderRegistry providers();

    /** @return the tool registry. */
    ToolRegistry tools();

    /** @return the agent template registry. */
    AgentTemplateRegistry templates();

    /** @return the hook bus. */
    HookBus hooks();

    /** @return the instrument registry. */
    InstrumentRegistry instruments();

    /** @return the background job registry. */
    JobRegistry jobs();

    /** @return the namespace table. */
    NamespaceRegistry namespaces();

    /** @return whether the runtime is started. */
    boolean isRunning();

    /** Bake mods and start the supervisor. Idempotent. */
    void start();

    /**
     * Create an agent through the supervisor.
     *
     * @param request creation input
     * @return the running agent
     * @throws IllegalStateException when the runtime is not started
     */
    Agent spawn(AgentRequest request);

    /**
     * Create an agent as a child of {@code parent}.
     *
     * <p>This is what {@code subagent} and {@code agent_spawn} use: the child inherits the parent's
     * namespace, budget, and attenuated capabilities, so it is a real node in the supervision
     * tree rather than a second root.</p>
     *
     * @param parent  the parent agent
     * @param request creation input; its {@code parentPid}/{@code namespaceId} are filled in
     * @return the running child
     */
    Agent spawnFrom(Agent parent, AgentRequest request);

    /**
     * Create an agent from a registered template.
     *
     * @param templateId template id
     * @param name       agent name, or {@code null}
     * @param workspace  working directory, or {@code null} for a fresh one
     * @return the running agent
     */
    default Agent spawn(String templateId, String name, Path workspace) {
        return spawn(AgentRequest.builder(templateId).name(name).workspace(workspace).build());
    }

    /** @return the agent with this id, if any agent is registered under it. */
    default Optional<Agent> agent(String agentId) {
        return supervisor().agent(agentId);
    }

    @Override
    void close();
}
