package ch.predmet.jarness.api.observe;

import ch.predmet.jarness.api.session.EventRecord;

import java.util.List;
import java.util.function.Consumer;

/**
 * Observability over the committed image: replay and a change feed built from the event log.
 *
 * <p>Only committed turns produce events, so a subscriber never sees a turn that was rolled back.
 * The feed is a daemon-local CDC; a cold/offline observer is out of scope for v1.</p>
 */
public interface Observation extends AutoCloseable {

    /**
     * Read committed events.
     *
     * @param sessionId session id
     * @param fromTurn  first turn index
     * @return events in commit order
     */
    List<EventRecord> events(String sessionId, int fromTurn);

    /**
     * Subscribe to new events of one session. Existing events are not replayed; use
     * {@link #events} for that.
     *
     * @param sessionId session id
     * @param consumer  callback
     * @return a registration whose {@code close()} stops delivery
     */
    AutoCloseable subscribe(String sessionId, Consumer<EventRecord> consumer);

    @Override
    void close();
}
