package ch.predmet.jarness.api.supervisor;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentSignal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The singleton that owns running agents and, in a very real sense, owns the runtime's life.
 *
 * <p>The runtime starts it as soon as the mod loader has baked everything and stops it last; it must
 * live at least once even when zero agents are ever spawned, so that an embedding host can ask
 * "what is running?" at any point. Agents created through {@link #spawn(AgentRequest)} register
 * themselves automatically; agents built by hand call {@link #register(Agent)}.</p>
 */
public interface Supervisor extends AutoCloseable {

    /** Start accepting agents. Idempotent. */
    void start();

    /** @return whether the supervisor is running. */
    boolean isRunning();

    /** @return when the supervisor started, or {@code null} before {@link #start()}. */
    Instant startedAt();

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
     * Remove an agent from the registry and stop it.
     *
     * @param agentId agent id
     * @return whether an agent was removed
     */
    boolean unregister(String agentId);

    /** @return every agent, in registration order. */
    List<Agent> agents();

    /** @return the agent with this id, if registered. */
    Optional<Agent> agent(String agentId);

    /** @return a status snapshot of every agent, in registration order. */
    List<AgentStatus> statuses();

    /** @return the status of one agent, if registered. */
    Optional<AgentStatus> status(String agentId);

    /**
     * Deliver a control signal to one agent.
     *
     * @param agentId agent id
     * @param signal  the signal
     * @return whether the agent existed
     */
    boolean signal(String agentId, AgentSignal signal);

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

    /** @return the number of registered agents. */
    int size();

    /** Stop every agent and shut the supervisor down. Idempotent. */
    void shutdown();

    @Override
    default void close() {
        shutdown();
    }
}
