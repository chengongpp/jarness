package ch.predmet.jarness.api.session;

import java.time.Instant;

/**
 * One provider step inside a turn: one completion plus the tool calls it requested.
 *
 * @param index         0-based step index within the turn
 * @param startedAt     start time
 * @param endedAt       end time, or {@code null} while running
 * @param finishReason  provider finish reason
 * @param promptTokens  prompt tokens reported for this step
 * @param completionTokens completion tokens reported for this step
 * @param toolCalls     number of tool calls issued in this step
 */
public record Step(int index, Instant startedAt, Instant endedAt, String finishReason,
                   int promptTokens, int completionTokens, int toolCalls) {

    /** @return a running step with zeroed counters. */
    public static Step started(int index) {
        return new Step(index, Instant.now(), null, null, 0, 0, 0);
    }

    /** @return a finished copy with the observed counters. */
    public Step finished(String finishReason, int promptTokens, int completionTokens, int toolCalls) {
        return new Step(index, startedAt, Instant.now(), finishReason, promptTokens, completionTokens, toolCalls);
    }
}
