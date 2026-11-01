package ch.predmet.jarness.api.supervisor;

import ch.predmet.jarness.api.agent.AgentState;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Read-only snapshot of one registered agent.
 *
 * <p>The supervisor guarantees that this view is available for every agent at all times, which is
 * what makes a multi-agent runtime observable: an outside observer can enumerate agents, see what
 * they are doing, and signal them without holding their references.</p>
 *
 * @param agentId          agent id string
 * @param pid              process id
 * @param parentPid        parent pid, {@code 0} for the supervisor root
 * @param namespaceId      namespace id
 * @param depth            tree depth, {@code 1} for a direct child of the root
 * @param name             display name
 * @param templateId       template id
 * @param state            lifecycle state
 * @param sessionId        current session id
 * @param workspace        working directory
 * @param turns            accepted turns
 * @param steps            provider steps executed
 * @param toolCalls        tool calls executed
 * @param promptTokens     prompt tokens consumed
 * @param completionTokens completion tokens produced
 * @param queuedInputs     inputs waiting in the agent's queue
 * @param startedAt        agent start time
 * @param lastActivityAt   last state change
 * @param error            last error, or {@code null}
 */
public record AgentStatus(String agentId, long pid, long parentPid, String namespaceId, int depth,
                          String name, String templateId, AgentState state, String sessionId,
                          Path workspace, int turns, int steps, int toolCalls, int promptTokens,
                          int completionTokens, int queuedInputs, Instant startedAt,
                          Instant lastActivityAt, String error) {
}
