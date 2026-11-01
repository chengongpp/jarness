package ch.predmet.jarness.api.supervisor;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentSignal;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.kernel.AgentManifest;
import ch.predmet.jarness.api.kernel.AgentTree;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.ipc.Blackboard;
import ch.predmet.jarness.api.ipc.Channel;
import ch.predmet.jarness.api.kernel.ImageRef;
import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.api.kernel.ExitStatus;
import ch.predmet.jarness.api.kernel.ResourceStats;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceRegistry;
import ch.predmet.jarness.api.namespace.NamespaceSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The singleton that owns the supervision tree and, in a very real sense, owns the runtime's life.
 *
 * <p>The runtime starts it as soon as the mod loader has baked everything and stops it last; it must
 * live at least once even when zero agents are ever spawned, so that an embedding host can ask
 * "what is running?" at any point.</p>
 *
 * <p>Agents are created only through {@link #spawn(long, String, AgentManifest, CapabilitySet, Budget)}
 * or {@link #fork(long, String, AgentManifest, CapabilitySet, Budget)}. Both validate depth, width,
 * namespace boundary, capability attenuation, and budget before a new node is admitted; a refusal
 * raises {@link ch.predmet.jarness.api.kernel.SpawnDeniedException} and is emitted as an event.
 * {@link #spawn(AgentRequest)} is the ergonomic wrapper used by the CLI, the TUI, and the
 * agent-spawning tools; it registers the agent and returns it.</p>
 */
public interface Supervisor extends AutoCloseable {

    /** @return the namespace table this supervisor spawns into. */
    NamespaceRegistry namespaces();

    /**
     * @return the platform super agent at pid 0, if it has been created
     */
    Optional<Agent> superAgent();

    /**
     * Create the platform super agent at pid 0 if it does not exist.
     *
     * @return the super agent
     */
    Agent ensureSuperAgent();

    /** Start accepting agents. Idempotent. */
    void start();

    /** @return whether the supervisor is running. */
    boolean isRunning();

    /** @return when the supervisor started, or {@code null} before {@link #start()}. */
    Instant startedAt();

    /**
     * Create an agent as a child of {@code parentPid}, from a template with an empty context.
     *
     * @param parentPid     parent pid, {@code 0} for a direct child of the supervisor root
     * @param namespaceId   target namespace, or {@code null} to use the parent's
     * @param manifest      what to instantiate and under which policy
     * @param requestedCaps capabilities to request, or {@code null} to inherit the parent's
     * @param budget        requested budget, or {@code null} for the child default
     * @return the new pid
     * @throws ch.predmet.jarness.api.kernel.SpawnDeniedException when the spawn is refused
     */
    long spawn(long parentPid, String namespaceId, AgentManifest manifest, CapabilitySet requestedCaps,
               Budget budget);

    /**
     * Create an agent as a child of {@code parentPid}, copying the parent's image.
     *
     * @param parentPid     parent pid
     * @param namespaceId   target namespace, or {@code null} to use the parent's
     * @param manifest      what to instantiate and under which policy
     * @param requestedCaps capabilities to request, or {@code null} to inherit the parent's
     * @param budget        requested budget, or {@code null} for the child default
     * @return the new pid
     * @throws ch.predmet.jarness.api.kernel.SpawnDeniedException when the spawn is refused
     */
    long fork(long parentPid, String namespaceId, AgentManifest manifest, CapabilitySet requestedCaps,
              Budget budget);

    /**
     * Create an agent, register it, and start it.
     *
     * @param request creation input
     * @return the running agent
     */
    Agent spawn(AgentRequest request);

    /**
     * Register an externally constructed agent.
     *
     * @param agent the agent; its {@code supervisor()} must be this supervisor
     */
    void register(Agent agent);

    /**
     * Remove an agent from the registry and stop it, together with its subtree.
     *
     * @param agentId agent id
     * @return whether an agent was removed
     */
    boolean unregister(String agentId);

    /** @return every agent, in registration order. */
    List<Agent> agents();

    /** @return the agent with this id, if registered. */
    Optional<Agent> agent(String agentId);

    /** @return the agent with this pid, if registered. */
    Agent agent(long pid);

    /** @return a status snapshot of every agent, in registration order. */
    List<AgentStatus> statuses();

    /** @return the status of one agent, if registered. */
    Optional<AgentStatus> status(String agentId);

    /** @return the status of one agent, if registered. */
    Optional<AgentStatus> status(long pid);

    /**
     * Deliver a control signal to one agent.
     *
     * @param agentId agent id
     * @param signal  the signal
     * @return whether the agent existed
     */
    boolean signal(String agentId, AgentSignal signal);

    /**
     * Deliver a control signal to one agent, optionally to its whole subtree.
     *
     * @param pid     process id
     * @param signal  the signal
     * @param subtree whether to deliver to descendants too
     * @return whether the agent existed
     */
    boolean signal(long pid, AgentSignal signal, boolean subtree);

    /**
     * Deliver a control signal to every agent.
     *
     * @param signal the signal
     */
    void signalAll(AgentSignal signal);

    /**
     * Queue a user input on a running agent, the supervisor-level entry point for inter-agent
     * messaging.
     *
     * @param agentId agent id
     * @param text    the message text
     * @return whether the agent accepted it
     */
    boolean deliver(String agentId, String text);

    /**
     * Wait until every agent is idle or stopped.
     *
     * @param timeout maximum wait
     * @return whether the supervisor drained within the timeout
     */
    boolean awaitAll(Duration timeout);

    /**
     * Block until one agent exits.
     *
     * @param pid     process id
     * @param timeout maximum wait
     * @return the exit status, or empty on timeout or unknown pid
     */
    Optional<ExitStatus> await(long pid, Duration timeout);

    /**
     * @param pid process id, {@code 0} for the supervisor root
     * @return a snapshot of the subtree rooted at {@code pid}
     * @throws IllegalArgumentException when no such node exists
     */
    AgentTree tree(long pid);

    /**
     * @param pid process id, {@code 0} for the supervisor root
     * @return aggregated counters for the subtree
     * @throws IllegalArgumentException when no such node exists
     */
    ResourceStats stats(long pid);

    /**
     * Revoke a capability grant and every grant derived from it.
     *
     * @param capId the grant id
     * @return whether the grant was known
     */
    boolean revokeCap(String capId);

    /**
     * Checkpoint an agent's image and compact the turn deltas it covers.
     *
     * @param pid process id
     * @return a reference to the checkpointed image
     */
    ImageRef checkpoint(long pid);

    /**
     * Checkpoint an agent, then stop it and mark the node suspended.
     *
     * @param pid process id
     * @return a reference to the checkpointed image
     */
    ImageRef suspend(long pid);

    /**
     * Resume an image as a root agent in its namespace.
     *
     * <p>Dependencies are verified first; when they no longer match, the spawn is refused with
     * {@link ch.predmet.jarness.api.session.ResumeDeniedException} unless the image is loaded
     * through a request carrying {@code forceResume}.</p>
     *
     * @param image the image reference
     * @return the resumed agent
     */
    Agent resume(ImageRef image);

    /**
     * Load a mod jar into the running runtime.
     *
     * @param jar the mod jar
     * @return the load report
     */
    default LoadReport loadDynamic(java.nio.file.Path jar) {
        throw new UnsupportedOperationException("this supervisor does not support dynamic loading");
    }

    /**
     * Create a blackboard owned by an agent.
     *
     * @param owner owner
     * @param id    board id
     * @return the board
     */
    default Blackboard createBoard(Agent owner, String id) {
        throw new UnsupportedOperationException("this supervisor does not expose IPC handles");
    }

    /**
     * Create a channel owned by an agent.
     *
     * @param owner    owner
     * @param capacity buffer capacity; 0 for rendezvous
     * @return the channel
     */
    default Channel createChannel(Agent owner, int capacity) {
        throw new UnsupportedOperationException("this supervisor does not expose IPC handles");
    }

    /**
     * Create a namespace.
     *
     * @param spec creation input
     * @return the created namespace
     */
    Namespace createNamespace(NamespaceSpec spec);

    /**
     * Remove a namespace.
     *
     * @param id namespace id
     * @return whether a namespace was removed
     */
    boolean removeNamespace(String id);

    /** @return the number of registered agents. */
    int size();

    /** Stop every agent and shut the supervisor down. Idempotent. */
    void shutdown();

    @Override
    default void close() {
        shutdown();
    }
}
