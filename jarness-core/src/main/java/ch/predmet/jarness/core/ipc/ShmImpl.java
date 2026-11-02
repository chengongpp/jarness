package ch.predmet.jarness.core.ipc;

import ch.predmet.jarness.api.ipc.Cell;
import ch.predmet.jarness.api.ipc.Shm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Per-owner handle to versioned, CAS-friendly shared memory.
 *
 * <p>The cells, watchers, locks and SQLite store are shared, while the owner pid and closed flag are
 * handle-local. Attaching another agent therefore cannot change the attribution of this handle's
 * writes, and closing one handle does not close the namespace's shared state.</p>
 */
final class ShmImpl implements Shm {

    private final String id;
    private final String namespaceId;
    private final long creatorPid;
    private final long ownerPid;
    private final SqliteShmStore store;
    private final State state;
    private final List<AutoCloseable> registrations = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /** State shared by every handle to one namespace/id. */
    private static final class State {
        private final Map<String, Cell> cells = new ConcurrentHashMap<>();
        private final Map<String, List<Consumer<Cell>>> watchers = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        private volatile boolean closed;

        private State(Map<String, Cell> initial) {
            cells.putAll(initial);
        }
    }

    ShmImpl(String id, String namespaceId, long creatorPid, SqliteShmStore store,
            Map<String, Cell> initial) {
        this(id, namespaceId, creatorPid, creatorPid, store, new State(initial));
    }

    private ShmImpl(String id, String namespaceId, long creatorPid, long ownerPid,
                    SqliteShmStore store, State state) {
        this.id = id;
        this.namespaceId = namespaceId;
        this.creatorPid = creatorPid;
        this.ownerPid = ownerPid;
        this.store = store;
        this.state = state;
    }

    /** @return an independent handle whose mutations are attributed to {@code pid}. */
    ShmImpl forOwner(long pid) {
        return new ShmImpl(id, namespaceId, creatorPid, pid, store, state);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String namespaceId() {
        return namespaceId;
    }

    @Override
    public Optional<Cell> read(String cell) {
        synchronized (state) {
            checkOpen();
            return Optional.ofNullable(state.cells.get(cell));
        }
    }

    @Override
    public Cell write(String cell, Object value) {
        synchronized (state) {
            checkOpen();
            Cell current = state.cells.get(cell);
            long version = current == null ? 1 : current.version() + 1;
            Cell next = new Cell(cell, version, value, Instant.now(), ownerPid);
            persist(next);
            state.cells.put(cell, next);
            notifyWatchers(next);
            return next;
        }
    }

    @Override
    public Optional<Cell> compareAndSet(String cell, long expectedVersion, Object value) {
        synchronized (state) {
            checkOpen();
            Cell current = state.cells.get(cell);
            long actual = current == null ? 0 : current.version();
            if (actual != expectedVersion) {
                return Optional.empty();
            }
            long version = expectedVersion + 1;
            Cell next = new Cell(cell, version, value, Instant.now(), ownerPid);
            persist(next);
            state.cells.put(cell, next);
            notifyWatchers(next);
            return Optional.of(next);
        }
    }

    @Override
    public boolean delete(String cell) {
        synchronized (state) {
            checkOpen();
            Cell removed = state.cells.get(cell);
            if (removed == null) {
                return false;
            }
            if (store != null) {
                store.deleteCell(namespaceId, id, cell);
            }
            state.cells.remove(cell);
            notifyWatchers(new Cell(cell, removed.version() + 1, null, Instant.now(), ownerPid));
            return true;
        }
    }

    @Override
    public Map<String, Cell> snapshot() {
        synchronized (state) {
            checkOpen();
            return Map.copyOf(state.cells);
        }
    }

    @Override
    public Set<String> cells() {
        synchronized (state) {
            checkOpen();
            return Set.copyOf(state.cells.keySet());
        }
    }

    @Override
    public AutoCloseable watch(String cell, Consumer<Cell> watcher) {
        checkOpen();
        List<Consumer<Cell>> list = state.watchers.computeIfAbsent(cell,
                key -> new CopyOnWriteArrayList<>());
        list.add(watcher);
        AutoCloseable registration = () -> {
            List<Consumer<Cell>> current = state.watchers.get(cell);
            if (current != null) {
                current.remove(watcher);
            }
        };
        registrations.add(registration);
        return registration;
    }

    @Override
    public AutoCloseable lock(String cell) {
        checkOpen();
        ReentrantLock lock = state.locks.computeIfAbsent(cell, key -> new ReentrantLock());
        lock.lock();
        return () -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        };
    }

    private void persist(Cell cell) {
        if (store != null) {
            store.put(namespaceId, id, cell);
        }
    }

    /** Delete the persisted board and invalidate every handle. */
    void deletePersisted() {
        if (store != null) {
            store.delete(namespaceId, id);
        }
        shutdown();
    }

    /** Invalidate every handle without deleting durable cells. */
    void shutdown() {
        state.closed = true;
        state.watchers.clear();
        state.cells.clear();
        state.locks.clear();
    }

    private void notifyWatchers(Cell cell) {
        List<Consumer<Cell>> list = state.watchers.get(cell.name());
        if (list == null) {
            return;
        }
        for (Consumer<Cell> watcher : list) {
            try {
                watcher.accept(cell);
            } catch (Throwable ignored) {
                // a watcher must not break shared memory
            }
        }
    }

    private void checkOpen() {
        if (closed || state.closed) {
            throw new IllegalStateException("shm " + namespaceId + "/" + id + " is closed");
        }
    }

    @Override
    public boolean closed() {
        return closed || state.closed;
    }

    /** Close only this owner's handle; shared cells remain available to other handles. */
    @Override
    public void close() {
        closed = true;
        for (AutoCloseable registration : registrations) {
            try {
                registration.close();
            } catch (Exception ignored) {
                // best effort while detaching this handle
            }
        }
        registrations.clear();
    }

    @Override
    public String toString() {
        return "Shm[" + namespaceId + "/" + id + ", creator=" + creatorPid + ", owner=" + ownerPid + "]";
    }
}
