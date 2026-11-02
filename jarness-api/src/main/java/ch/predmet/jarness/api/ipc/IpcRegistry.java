package ch.predmet.jarness.api.ipc;

import ch.predmet.jarness.api.agent.Agent;

import java.util.List;
import java.util.Optional;

/**
 * The runtime's collaboration primitive table.
 *
 * <p>Shm handles are capabilities: creating or attaching one requires the {@code ipc} capability.
 * Shms are namespace-scoped and persist their cells in the local store; a daemon restart keeps the
 * data, while channels are in-memory rendezvous handles and do not survive it.</p>
 */
public interface IpcRegistry {

    /**
     * Create a shm owned by an agent.
     *
     * @param owner owner
     * @param id    shm id, unique within the owner's namespace
     * @return the shm
     * @throws IllegalStateException when the id already exists in that namespace
     */
    Shm createShm(Agent owner, String id);

    /**
     * Attach to an existing shm, creating it when absent.
     *
     * @param owner owner
     * @param id    shm id
     * @return the shm
     */
    Shm attachShm(Agent owner, String id);

    /**
     * Find an existing shm and return a handle owned by {@code owner}. No shm is created when absent.
     * The owner must hold the {@code ipc} capability.
     *
     * @param owner handle owner
     * @param id    shm id in the owner's namespace
     * @return the shm, if any
     */
    Optional<Shm> findShm(Agent owner, String id);

    /** @return every shm id in a namespace. */
    List<String> shms(String namespaceId);

    /**
     * Remove a shm and its persisted cells.
     *
     * @param namespaceId namespace id
     * @param id          shm id
     * @return whether a shm was removed
     */
    boolean removeShm(String namespaceId, String id);

    /**
     * Create a channel owned by an agent.
     *
     * @param owner    owner
     * @param capacity buffer capacity; 0 for rendezvous
     * @return the channel
     */
    Channel createChannel(Agent owner, int capacity);

    /** @return the channel with this id, if any. */
    Optional<Channel> channel(String id);

    /** @return every channel id. */
    List<String> channels();

    /**
     * Remove a channel.
     *
     * @param id channel id
     * @return whether a channel was removed
     */
    boolean removeChannel(String id);

    /**
     * Close every channel owned by a pid.
     *
     * <p>Shms are shared namespace state rather than an agent's private handle: their creator
     * exiting does not erase the cells. Use {@link #removeShm(String, String)} to delete one.</p>
     *
     * @param pid owner pid
     */
    void closeOwned(long pid);

    /** Close every shm handle and channel. Persisted shm cells stay in the store. */
    void close();
}
