package ch.predmet.jarness.api.session;

import java.time.Instant;
import java.util.List;

/**
 * One user input and everything the agent did about it: the unit the supervisor schedules and the
 * unit the session store persists.
 */
public interface Turn {

    /** @return turn id. */
    String id();

    /** @return owning session id. */
    String sessionId();

    /** @return 0-based index within the session. */
    int index();

    /** @return current turn state. */
    TurnState state();

    /** @return the user input that opened the turn. */
    String input();

    /** @return the final assistant text, or an empty string while running. */
    String output();

    /** @return the error that ended the turn, or {@code null}. */
    String error();

    /** @return start time. */
    Instant startedAt();

    /** @return end time, or {@code null} while running. */
    Instant endedAt();

    /** @return provider steps executed in this turn. */
    List<Step> steps();

    /** @return prompt tokens summed over steps. */
    int promptTokens();

    /** @return completion tokens summed over steps. */
    int completionTokens();

    /**
     * Record a finished step.
     *
     * @param step the step to append
     */
    void addStep(Step step);

    /**
     * Finish the turn.
     *
     * @param state  terminal state
     * @param output final assistant text
     * @param error  error message, or {@code null}
     */
    void finish(TurnState state, String output, String error);
}
