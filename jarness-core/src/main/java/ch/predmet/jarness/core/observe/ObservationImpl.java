package ch.predmet.jarness.core.observe;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.observe.Observation;
import ch.predmet.jarness.api.session.EventRecord;
import ch.predmet.jarness.api.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
 * Polling CDC over committed event logs in every namespace store.
 *
 * <p>The session-only API discovers the owning store from the image table. The namespace overload
 * is explicit and avoids discovery for embedders; both share the same subscription machinery.</p>
 */
public final class ObservationImpl implements Observation {

    private static final Logger LOG = LoggerFactory.getLogger(ObservationImpl.class);

    private final SessionStore defaultStore;
    private final java.util.function.Supplier<JarnessRuntime> runtime;
    private final Map<String, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seen = new ConcurrentHashMap<>();
    private final Map<String, SessionStore> resolved = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller;
    private volatile boolean closed;

    private record Subscriber(Consumer<EventRecord> consumer) {
    }

    public ObservationImpl(SessionStore defaultStore, java.util.function.Supplier<JarnessRuntime> runtime,
                           Duration interval) {
        this.defaultStore = defaultStore;
        this.runtime = runtime;
        this.poller = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofVirtual().name("jarness-observation").unstarted(runnable));
        long millis = Math.max(50, interval == null ? 250 : interval.toMillis());
        poller.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<EventRecord> events(String sessionId, int fromTurn) {
        return storeFor(null, sessionId).events(sessionId, fromTurn);
    }

    @Override
    public List<EventRecord> events(String namespaceId, String sessionId, int fromTurn) {
        return storeFor(namespaceId, sessionId).events(sessionId, fromTurn);
    }

    @Override
    public AutoCloseable subscribe(String sessionId, Consumer<EventRecord> consumer) {
        return subscribe(null, sessionId, consumer);
    }

    @Override
    public AutoCloseable subscribe(String namespaceId, String sessionId, Consumer<EventRecord> consumer) {
        if (closed) {
            throw new IllegalStateException("observation is closed");
        }
        String key = key(namespaceId, sessionId);
        Subscriber subscriber = new Subscriber(consumer);
        subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        SessionStore store = storeFor(namespaceId, sessionId);
        resolved.put(key, store);
        // Baseline: do not replay the past through the live feed.
        Set<String> ids = seen.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
        for (EventRecord event : store.events(sessionId, 0)) {
            ids.add(event.id());
        }
        LOG.debug("observation subscriber attached to session {} in namespace {}", sessionId,
                namespaceId == null ? "<discovered>" : namespaceId);
        return () -> {
            List<Subscriber> list = subscribers.get(key);
            if (list != null) {
                list.remove(subscriber);
            }
        };
    }

    private SessionStore storeFor(String namespaceId, String sessionId) {
        String key = key(namespaceId, sessionId);
        SessionStore cached = resolved.get(key);
        if (cached != null) {
            return cached;
        }
        if (namespaceId != null && !namespaceId.isBlank()) {
            SessionStore store = runtime.get().namespaces().get(namespaceId).store();
            resolved.put(key, store);
            return store;
        }
        for (Namespace namespace : runtime.get().namespaces().list()) {
            SessionStore candidate = namespace.store();
            try {
                if (candidate.image(sessionId).isPresent() || !candidate.events(sessionId, 0).isEmpty()) {
                    resolved.put(key, candidate);
                    return candidate;
                }
            } catch (RuntimeException e) {
                LOG.debug("could not inspect session {} in namespace {}: {}", sessionId, namespace.id(),
                        e.getMessage());
            }
        }
        resolved.put(key, defaultStore);
        return defaultStore;
    }

    private void poll() {
        if (closed) {
            return;
        }
        for (Map.Entry<String, List<Subscriber>> entry : subscribers.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            String key = entry.getKey();
            String sessionId = key.substring(key.indexOf('\0') + 1);
            Set<String> ids = seen.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
            List<EventRecord> events;
            try {
                events = resolved.get(key).events(sessionId, 0);
            } catch (RuntimeException e) {
                LOG.debug("could not poll events of session {}: {}", sessionId, e.getMessage());
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

    private static String key(String namespaceId, String sessionId) {
        return (namespaceId == null ? "" : namespaceId) + '\0' + sessionId;
    }

    @Override
    public void close() {
        closed = true;
        poller.shutdownNow();
        subscribers.clear();
        seen.clear();
        resolved.clear();
    }
}
