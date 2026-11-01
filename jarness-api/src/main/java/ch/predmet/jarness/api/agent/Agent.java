package ch.predmet.jarness.api.agent;

import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.kernel.AgentManifest;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.kernel.ExitStatus;
import ch.predmet.jarness.api.mailbox.Mailbox;
import ch.predmet.jarness.api.mailbox.MailboxMessage;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.supervisor.AgentStatus;
import ch.predmet.jarness.api.supervisor.Supervisor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * One running agent.
 *
 * <p>Agents are runtime objects, not threads: the supervisor schedules their turns on its own
 * executor, and several agents coexist in one Jarness runtime. Every agent is registered with the
 * supervisor before it starts working, so its state is always visible to, and signalable from, the
 * outside.</p>
 *
 * <p>Every agent has a pid, a parent in the supervision tree, exactly one namespace, a capability
 * set, and a budget. The pid is the primary identity; {@link #id()} is a stable string form used in
 * logs and diagnostics.</p>
 */
public interface Agent extends AutoCloseable {

    /** @return agent id, unique within the runtime. */
    String id();

    /** @return process id, unique within the runtime; pids start at 1 and never restart. */
    long pid();

    /** @return the parent pid, {@code 0} for an agent spawned under the supervisor root. */
    long parentPid();

    /** @return the tree depth, {@code 1} for a direct child of the supervisor root. */
    int depth();

    /** @return the parent agent, or empty for a root agent. */
    default Optional<Agent> parent() {
        return parentPid() == 0 ? Optional.empty() : Optional.ofNullable(supervisor().agent(parentPid()));
    }

    /** @return the namespace this agent is bound to. */
    Namespace namespace();

    /** @return the namespace id, a shorthand for {@code namespace().id()}. */
    default String namespaceId() {
        return namespace().id();
    }

    /** @return the manifest this agent was created from. */
    AgentManifest manifest();

    /** @return the capabilities this agent currently holds. */
    CapabilitySet capabilities();

    /** @return the remaining budget. */
    Budget budget();

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

    /** @return this agent's mailbox. */
    Mailbox mailbox();

    /**
     * Offer a message to the mailbox.
     *
     * @param message the message
     * @return whether the mailbox accepted it
     */
    default boolean deliver(MailboxMessage message) {
        return mailbox().offer(message);
    }

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

    /**
     * Block until the agent has exited.
     *
     * @param timeout maximum wait
     * @return the exit status, or empty when the agent is still running
     */
    Optional<ExitStatus> awaitExit(Duration timeout);

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
