package ch.predmet.jarness.api.agent;

import ch.predmet.jarness.api.session.TurnState;

import java.time.Duration;

/**
 * Outcome of one turn, as returned to whoever submitted it.
 *
 * @param agentId          the agent that ran the turn
 * @param sessionId        the session the turn belongs to
 * @param turnId           the turn id
 * @param turnIndex        0-based turn index inside the session
 * @param state            terminal turn state
 * @param output           final assistant text
 * @param error            error text when the turn failed
 * @param steps            provider steps executed
 * @param promptTokens     prompt tokens consumed
 * @param completionTokens completion tokens produced
 * @param duration         wall time of the turn
 */
public record TurnResult(String agentId, String sessionId, String turnId, int turnIndex, TurnState state,
                         String output, String error, int steps, int promptTokens, int completionTokens,
                         Duration duration) {

    /** @return whether the turn ended without error. */
    public boolean ok() {
        return state == TurnState.COMPLETED;
    }
}
