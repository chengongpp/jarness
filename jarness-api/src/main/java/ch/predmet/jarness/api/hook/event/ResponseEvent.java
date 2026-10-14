package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.provider.ChatResponse;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.Turn;

/**
 * Mutable payload for {@code AFTER_RESPONSE}: the normalized completion may still be replaced.
 */
public final class ResponseEvent {

    private final Agent agent;
    private final Session session;
    private final Turn turn;
    private final int step;
    private ChatResponse response;

    /**
     * @param agent    the agent
     * @param session  its session
     * @param turn     the running turn
     * @param step     step index
     * @param response the normalized completion
     */
    public ResponseEvent(Agent agent, Session session, Turn turn, int step, ChatResponse response) {
        this.agent = agent;
        this.session = session;
        this.turn = turn;
        this.step = step;
        this.response = response;
    }

    /** @return the agent. */
    public Agent agent() {
        return agent;
    }

    /** @return the session. */
    public Session session() {
        return session;
    }

    /** @return the running turn. */
    public Turn turn() {
        return turn;
    }

    /** @return the step index. */
    public int step() {
        return step;
    }

    /** @return the completion. */
    public ChatResponse response() {
        return response;
    }

    /** Replace the completion. */
    public void setResponse(ChatResponse value) {
        if (value != null) {
            this.response = value;
        }
    }
}
