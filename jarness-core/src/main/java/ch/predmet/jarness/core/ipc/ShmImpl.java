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
 * Versioned, CAS-friendly shared memory.
 *
 * <p>Reads hit the in-memory map; writes and deletes are written through to
 * {@link SqliteShmStore} before the in-memory version changes, so a committed cell survives a
 * runtime restart. The lock table and watchers are deliberately process-local.</p>
 */
final class ShmImpl implements Shm {

    private final String id;
    private final String namespaceId;
    private final long creatorPid;
    private final SqliteShmStore store;
    private final Map<String, Cell> cells = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Cell>>> watchers = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private volatile long actorPid;
    private volatile boolean closed;

    ShmImpl(String id, String namespaceId, long creatorPid, SqliteShmStore store,
            Map<String, Cell> initial) {
        this.id = id;
        this.namespaceId = namespaceId;
        this.creatorPid = creatorPid;
        this.actorPid = creatorPid;
        this.store = store;
        this.cells.putAll(initial);
    }

    /** Bind the next mutation to the agent that attached this handle. */
    synchronized void actor(long pid) {
        this.actorPid = pid;
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
    public synchronized Optional<Cell> read(String cell) {
        checkOpen();
        return Optional.ofNullable(cells.get(cell));
    }

    @Override
    public synchronized Cell write(String cell, Object value) {
        checkOpen();
        Cell current = cells.get(cell);
        long version = current == null ? 1 : current.version() + 1;
        Cell next = new Cell(cell, version, value, Instant.now(), actorPid);
        persist(next);
        cells.put(cell, next);
        notifyWatchers(next);
        return next;
    }

    @Override
    public synchronized Optional<Cell> compareAndSet(String cell, long expectedVersion, Object value) {
        checkOpen();
        Cell current = cells.get(cell);
        long actual = current == null ? 0 : current.version();
        if (actual != expectedVersion) {
            return Optional.empty();
        }
        long version = expectedVersion + 1;
        Cell next = new Cell(cell, version, value, Instant.now(), actorPid);
        persist(next);
        cells.put(cell, next);
        notifyWatchers(next);
        return Optional.of(next);
    }

    @Override
    public synchronized boolean delete(String cell) {
        checkOpen();
        Cell removed = cells.get(cell);
        if (removed == null) {
            return false;
        }
        if (store != null) {
            store.deleteCell(namespaceId, id, cell);
        }
        cells.remove(cell);
        notifyWatchers(new Cell(cell, removed.version() + 1, null, Instant.now(), actorPid));
        return true;
    }

    @Override
    public Map<String, Cell> snapshot() {
        checkOpen();
        return Map.copyOf(cells);
    }

    @Override
    public Set<String> cells() {
        checkOpen();
        return Set.copyOf(cells.keySet());
    }

    @Override
    public AutoCloseable watch(String cell, Consumer<Cell> watcher) {
        checkOpen();
        List<Consumer<Cell>> list = watchers.computeIfAbsent(cell, key -> new CopyOnWriteArrayList<>());
        list.add(watcher);
        return () -> {
            List<Consumer<Cell>> current = watchers.get(cell);
            if (current != null) {
                current.remove(watcher);
            }
        };
    }

    @Override
    public AutoCloseable lock(String cell) {
        checkOpen();
        ReentrantLock lock = locks.computeIfAbsent(cell, key -> new ReentrantLock());
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

    /** Delete the persisted board; called only through {@link IpcRegistryImpl#removeShm}. */
    void deletePersisted() {
        if (store != null) {
            store.delete(namespaceId, id);
        }
    }

    private void notifyWatchers(Cell cell) {
        List<Consumer<Cell>> list = watchers.get(cell.name());
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
        if (closed) {
            throw new IllegalStateException("shm " + namespaceId + "/" + id + " is closed");
        }
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        watchers.clear();
        cells.clear();
        locks.clear();
    }

    @Override
    public String toString() {
        return "Shm[" + namespaceId + "/" + id + (creatorPid >= 0 ? ", creator=" + creatorPid : "") + "]";
    }
}
