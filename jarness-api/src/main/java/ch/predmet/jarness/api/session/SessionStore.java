package ch.predmet.jarness.api.session;

import java.util.List;
import java.util.Optional;

/**
 * Durable session storage and the agent image.
 *
 * <p>SQLite is the default implementation. Custom stores can be supplied when constructing a
 * namespace programmatically; a dynamic-mod bootstrap SPI is not implemented yet. Implementations
 * must provide real turn transactions and image/event methods rather than relying on no-op defaults.</p>
 *
 * <p>The store is the durability boundary: a turn is written in one transaction when it commits,
 * so a crash in the middle of a turn leaves no half-written messages behind. Every implementation
 * must make {@link #commitTurn} atomic; there is deliberately no best-effort default.</p>
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

    /**
     * Commit a terminal turn atomically.
     *
     * <p>This is the durability boundary: the turn, the messages it produced, and its events become
     * visible together, or not at all.</p>
     *
     * @param session     owning session
     * @param turn        the terminal turn
     * @param newMessages messages produced during the turn, in wire order
     * @param image       image metadata to persist, or {@code null}
     */
    void commitTurn(Session session, Turn turn, List<Message> newMessages, ImageMetadata image);

    /**
     * @param sessionId owning session id
     * @return the image metadata of the session, if it has been written
     */
    Optional<ImageMetadata> image(String sessionId);

    /**
     * Persist image metadata without committing a turn, e.g. after a capability revocation.
     *
     * @param image the image metadata
     */
    void saveImage(ImageMetadata image);

    /**
     * Write a checkpoint of the session image and compact the covered deltas.
     *
     * @param session owning session
     * @param image   image metadata to update, or {@code null}
     */
    default void checkpoint(Session session, ImageMetadata image) {
        throw new UnsupportedOperationException("checkpoint is not supported by this session store");
    }

    /**
     * Read committed events of a session.
     *
     * @param sessionId owning session id
     * @param fromTurn  first turn index to include
     * @return events in insertion order
     */
    List<EventRecord> events(String sessionId, int fromTurn);

    @Override
    void close();
}
