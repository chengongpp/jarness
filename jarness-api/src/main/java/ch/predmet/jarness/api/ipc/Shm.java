package ch.predmet.jarness.api.ipc;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A named shared-memory area: versioned cells with CAS writes, kept in daemon memory and persisted
 * to the local store.
 *
 * <p>Shm is the shared-state primitive; protocols such as task boards or claim queues are left to
 * mods. A shm is scoped to one namespace: agents in other namespaces cannot see it even when they
 * know its name. Possessing a handle is what allows access; the runtime checks the {@code ipc}
 * capability before it hands one out.</p>
 */
public interface Shm extends AutoCloseable {

    /** @return shm id. */
    String id();

    /** @return the namespace that owns this shm. */
    String namespaceId();

    /**
     * @param cell cell name
     * @return the cell, or empty when it does not exist
     */
    Optional<Cell> read(String cell);

    /**
     * Write a cell unconditionally, bumping its version.
     *
     * @param cell  cell name
     * @param value value
     * @return the new cell
     */
    Cell write(String cell, Object value);

    /**
     * Compare-and-set a cell.
     *
     * @param cell            cell name
     * @param expectedVersion expected version; {@code 0} means "must not exist"
     * @param value           new value
     * @return the new cell, or empty on version conflict
     */
    Optional<Cell> compareAndSet(String cell, long expectedVersion, Object value);

    /**
     * Delete a cell.
     *
     * @param cell cell name
     * @return whether a cell was removed
     */
    boolean delete(String cell);

    /** @return every cell currently present. */
    Map<String, Cell> snapshot();

    /** @return the cell names currently present. */
    Set<String> cells();

    /**
     * Watch one cell.
     *
     * @param cell    cell name
     * @param watcher callback invoked after each successful write
     * @return a registration whose {@code close()} detaches the watcher
     */
    AutoCloseable watch(String cell, Consumer<Cell> watcher);

    /**
     * Take a pessimistic lock on one cell.
     *
     * @param cell cell name
     * @return a lock handle; closing it releases the lock
     */
    AutoCloseable lock(String cell);

    /** @return whether the handle is closed. */
    boolean closed();

    @Override
    void close();
}
