package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.Turn;

/**
 * Payload for turn and step points.
 *
 * @param agent   the agent
 * @param session the session
 * @param turn    the turn
 * @param step    0-based step index for {@code STEP_START}, or {@code null}
 */
public record TurnEvent(Agent agent, Session session, Turn turn, Integer step) {

    /** @return a turn-level event. */
    public static TurnEvent of(Agent agent, Session session, Turn turn) {
        return new TurnEvent(agent, session, turn, null);
    }

    /** @return a step-level event. */
    public static TurnEvent of(Agent agent, Session session, Turn turn, int step) {
        return new TurnEvent(agent, session, turn, step);
    }
}
