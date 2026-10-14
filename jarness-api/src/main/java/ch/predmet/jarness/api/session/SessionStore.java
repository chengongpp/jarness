package ch.predmet.jarness.api.session;

import java.util.List;
import java.util.Optional;

/**
 * Durable session storage.
 *
 * <p>SQLite is the default implementation, and the JDBC URL is configuration, so a mod may swap the
 * backing store without touching the agent loop.</p>
 */
public interface SessionStore extends AutoCloseable {

    /**
     * Create and persist a session.
     *
     * @param init creation input
     * @return the live session
     */
    Session create(SessionInit init);

    /**
     * Load a session with its messages and turns.
     *
     * @param id session id
     * @return the session, or empty when unknown
     */
    Optional<Session> load(String id);

    /**
     * Load a session, creating it when absent.
     *
     * @param init creation input used when the id is unknown
     * @return the session
     */
    default Session loadOrCreate(SessionInit init) {
        if (init.id() != null) {
            Optional<Session> existing = load(init.id());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return create(init);
    }

    /**
     * List recent sessions.
     *
     * @param limit maximum entries, newest first
     * @return session summaries
     */
    List<SessionSummary> list(int limit);

    /**
     * List the sessions of one agent.
     *
     * @param agentId owning agent id
     * @return session summaries, newest first
     */
    List<SessionSummary> listByAgent(String agentId);

    /**
     * Persist a message.
     *
     * @param session owning session
     * @param message the message to store
     */
    void saveMessage(Session session, Message message);

    /**
     * Persist a turn's current state.
     *
     * @param session owning session
     * @param turn    the turn to store
     */
    void saveTurn(Session session, Turn turn);

    /**
     * Persist session-level fields (title, model, updated_at).
     *
     * @param session the session to update
     */
    void update(Session session);

    /**
     * Delete a session and its history.
     *
     * @param id session id
     * @return whether a session was removed
     */
    boolean delete(String id);

    @Override
    void close();
}
