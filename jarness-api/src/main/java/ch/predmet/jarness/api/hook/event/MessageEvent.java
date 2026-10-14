package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;

/**
 * Mutable payload for {@code MESSAGE_APPENDED}: a hook may rewrite the message or drop it entirely.
 */
public final class MessageEvent {

    private final Agent agent;
    private final Session session;
    private Message message;
    private boolean dropped;

    /**
     * @param agent   the owning agent, or {@code null}
     * @param session the session
     * @param message the message about to be appended
     */
    public MessageEvent(Agent agent, Session session, Message message) {
        this.agent = agent;
        this.session = session;
        this.message = message;
    }

    /** @return the owning agent, or {@code null}. */
    public Agent agent() {
        return agent;
    }

    /** @return the session. */
    public Session session() {
        return session;
    }

    /** @return the message about to be appended. */
    public Message message() {
        return message;
    }

    /** Replace the message. */
    public void setMessage(Message value) {
        if (value != null) {
            this.message = value;
        }
    }

    /** Drop the message: it is not appended to the session. */
    public void drop() {
        this.dropped = true;
    }

    /** @return whether the message should be dropped. */
    public boolean dropped() {
        return dropped;
    }
}
