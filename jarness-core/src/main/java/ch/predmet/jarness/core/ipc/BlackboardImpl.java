package ch.predmet.jarness.core.ipc;

import ch.predmet.jarness.api.ipc.Blackboard;
import ch.predmet.jarness.api.ipc.Cell;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Versioned, CAS-friendly blackboard.
 */
final class BlackboardImpl implements Blackboard {

    private final String id;
    private final long ownerPid;
    private final Map<String, Cell> cells = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Cell>>> watchers = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    BlackboardImpl(String id, long ownerPid) {
        this.id = id;
        this.ownerPid = ownerPid;
    }

    @Override
    public String id() {
        return id;
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
        Cell next = new Cell(cell, version, value, Instant.now(), ownerPid);
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
        Cell next = new Cell(cell, version, value, Instant.now(), ownerPid);
        cells.put(cell, next);
        notifyWatchers(next);
        return Optional.of(next);
    }

    @Override
    public synchronized boolean delete(String cell) {
        checkOpen();
        Cell removed = cells.remove(cell);
        if (removed == null) {
            return false;
        }
        notifyWatchers(new Cell(cell, removed.version() + 1, null, Instant.now(), ownerPid));
        return true;
    }

    @Override
    public Map<String, Cell> snapshot() {
        return Map.copyOf(cells);
    }

    @Override
    public Set<String> cells() {
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

    private void notifyWatchers(Cell cell) {
        List<Consumer<Cell>> list = watchers.get(cell.name());
        if (list == null) {
            return;
        }
        for (Consumer<Cell> watcher : list) {
            try {
                watcher.accept(cell);
            } catch (Throwable ignored) {
                // a watcher must not break the board
            }
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("blackboard " + id + " is closed");
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
        return "Blackboard[" + id + "]";
    }
}
