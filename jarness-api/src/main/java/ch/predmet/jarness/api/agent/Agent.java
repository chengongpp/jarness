package ch.predmet.jarness.api.agent;

import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.supervisor.AgentStatus;
import ch.predmet.jarness.api.supervisor.Supervisor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * One running agent.
 *
 * <p>Agents are runtime objects, not threads: the supervisor schedules their turns on its own
 * executor, and several agents coexist in one Jarness runtime. Every agent is registered with the
 * supervisor before it starts working, so its state is always visible to, and signalable from, the
 * outside.</p>
 */
public interface Agent extends AutoCloseable {

    /** @return agent id, unique within the runtime. */
    String id();

    /** @return display name. */
    String name();

    /** @return the template this agent was created from. */
    AgentTemplate template();

    /** @return the agent's working directory; never the runtime base directory. */
    Path workspace();

    /** @return current lifecycle state. */
    AgentState state();

    /** @return the durable conversation. */
    Session session();

    /** @return the supervisor that owns this agent. */
    Supervisor supervisor();

    /** @return this agent's extension context. */
    AgentContext ctx();

    /** @return a logger tagged with the agent id. */
    Logger log();

    /**
     * Queue a user input and run it as the next turn.
     *
     * @param input the user input text
     * @return a future completing with the turn result
     */
    CompletableFuture<TurnResult> submit(String input);

    /**
     * Queue an already-built user message and run it as the next turn.
     *
     * @param input the user message
     * @return a future completing with the turn result
     */
    CompletableFuture<TurnResult> submit(Message input);

    /**
     * Deliver a control signal.
     *
     * @param signal the signal
     */
    void signal(AgentSignal signal);

    /** @return whether a turn is currently executing. */
    boolean isBusy();

    /** @return the number of accepted turns. */
    int turnCount();

    /**
     * Block until the agent has drained its queue.
     *
     * @param timeout maximum wait
     * @return whether the agent went idle within the timeout
     */
    boolean awaitIdle(Duration timeout);

    /** @return a read-only status snapshot. */
    AgentStatus status();

    /**
     * Stop the agent.
     *
     * @param reason short reason recorded on the agent and emitted to hooks
     */
    void stop(String reason);

    @Override
    default void close() {
        stop("closed");
    }
}
