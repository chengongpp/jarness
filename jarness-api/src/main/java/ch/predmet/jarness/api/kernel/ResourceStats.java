package ch.predmet.jarness.api.kernel;

import java.time.Duration;
import java.time.Instant;

/**
 * Aggregated counters for one node and its subtree.
 *
 * @param pid              process id
 * @param depth            tree depth, {@code 0} for the root
 * @param children         direct children
 * @param descendants      nodes below this one
 * @param turns            accepted turns
 * @param steps            provider steps
 * @param toolCalls        tool calls executed
 * @param promptTokens     prompt tokens consumed
 * @param completionTokens completion tokens produced
 * @param tokensLeft       remaining token budget, or {@code -1} for unlimited
 * @param startedAt        when the agent started
 * @param uptime           elapsed time since start
 */
public record ResourceStats(long pid, int depth, int children, int descendants, int turns, int steps,
                            int toolCalls, long promptTokens, long completionTokens, long tokensLeft,
                            Instant startedAt, Duration uptime) {
}
