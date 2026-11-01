package ch.predmet.jarness.core.observe;

import ch.predmet.jarness.api.observe.Observation;
import ch.predmet.jarness.api.session.EventRecord;
import ch.predmet.jarness.api.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polling CDC over the event log.
 *
 * <p>Events are written in the turn's transaction, so the feed never exposes rolled-back work.
 * Subscribers start at "now"; {@link #events} is the replay path.</p>
 */
public final class ObservationImpl implements Observation {

    private static final Logger LOG = LoggerFactory.getLogger(ObservationImpl.class);

    private final SessionStore store;
    private final Map<String, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seen = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller;
    private volatile boolean closed;

    private record Subscriber(Consumer<EventRecord> consumer) {
    }

    /**
     * @param store    image store
     * @param interval poll interval
     */
    public ObservationImpl(SessionStore store, Duration interval) {
        this.store = store;
        this.poller = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofVirtual().name("jarness-observation").unstarted(runnable));
        long millis = Math.max(50, interval == null ? 250 : interval.toMillis());
        poller.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<EventRecord> events(String sessionId, int fromTurn) {
        return store.events(sessionId, fromTurn);
    }

    @Override
    public AutoCloseable subscribe(String sessionId, Consumer<EventRecord> consumer) {
        if (closed) {
            throw new IllegalStateException("observation is closed");
        }
        Subscriber subscriber = new Subscriber(consumer);
        subscribers.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(subscriber);
        // Baseline: do not replay the past through the live feed.
        Set<String> ids = seen.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet());
        for (EventRecord event : store.events(sessionId, 0)) {
            ids.add(event.id());
        }
        LOG.debug("observation subscriber attached to session {}", sessionId);
        return () -> {
            List<Subscriber> list = subscribers.get(sessionId);
            if (list != null) {
                list.remove(subscriber);
            }
        };
    }

    private void poll() {
        if (closed) {
            return;
        }
        for (Map.Entry<String, List<Subscriber>> entry : subscribers.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Set<String> ids = seen.computeIfAbsent(entry.getKey(), key -> ConcurrentHashMap.newKeySet());
            List<EventRecord> events;
            try {
                events = store.events(entry.getKey(), 0);
            } catch (RuntimeException e) {
                LOG.debug("could not poll events of session {}: {}", entry.getKey(), e.getMessage());
                continue;
            }
            for (EventRecord event : events) {
                if (!ids.add(event.id())) {
                    continue;
                }
                for (Subscriber subscriber : entry.getValue()) {
                    try {
                        subscriber.consumer().accept(event);
                    } catch (Throwable t) {
                        LOG.warn("observation subscriber failed on event {}", event.id(), t);
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        poller.shutdownNow();
        subscribers.clear();
        seen.clear();
    }
}
