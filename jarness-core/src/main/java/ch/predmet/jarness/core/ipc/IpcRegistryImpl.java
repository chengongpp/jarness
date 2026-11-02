package ch.predmet.jarness.core.ipc;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.ipc.Channel;
import ch.predmet.jarness.api.ipc.IpcRegistry;
import ch.predmet.jarness.api.ipc.Shm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link IpcRegistry}: owns shm handles and channels, enforces the IPC capability on
 * creation and attachment, and keeps shm cells durable through {@link SqliteShmStore}.
 *
 * <p>Shms are namespace-scoped shared state. They survive the agent that created them and a daemon
 * restart; channels are process-local owner-bound handles and are closed when their owner exits.</p>
 */
public final class IpcRegistryImpl implements IpcRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(IpcRegistryImpl.class);

    private final Map<String, ShmImpl> shms = new LinkedHashMap<>();
    private final Map<String, ChannelImpl> channels = new LinkedHashMap<>();
    private final Map<Long, Set<String>> owned = new LinkedHashMap<>();
    private final SqliteShmStore store;

    /** Create an in-memory-only registry, useful for tests and short-lived embeddings. */
    public IpcRegistryImpl() {
        this.store = null;
    }

    /**
     * Create a registry whose shm cells are persisted in {@code database}.
     *
     * @param database SQLite file, usually {@code data/jarness.db}
     */
    public IpcRegistryImpl(Path database) {
        this.store = database == null ? null : SqliteShmStore.open(database);
    }

    @Override
    public synchronized Shm createShm(Agent owner, String id) {
        requireIpc(owner);
        String namespace = owner.namespaceId();
        requireId(id);
        if (store != null && store.exists(namespace, id)) {
            throw new IllegalStateException("shm " + namespace + "/" + id + " already exists");
        }
        if (store != null) {
            store.register(namespace, id, owner.pid());
        }
        ShmImpl shm = new ShmImpl(id, namespace, owner.pid(), store, Map.of());
        shms.put(key(namespace, id), shm);
        LOG.info("agent {} created shm {}/{}", owner.pid(), namespace, id);
        return shm.forOwner(owner.pid());
    }

    @Override
    public synchronized Shm attachShm(Agent owner, String id) {
        requireIpc(owner);
        String namespace = owner.namespaceId();
        requireId(id);
        String key = key(namespace, id);
        ShmImpl shm = shms.get(key);
        if (shm == null) {
            if (store != null && store.exists(namespace, id)) {
                shm = new ShmImpl(id, namespace, owner.pid(), store, store.load(namespace, id));
            } else {
                if (store != null) {
                    store.register(namespace, id, owner.pid());
                }
                shm = new ShmImpl(id, namespace, owner.pid(), store, Map.of());
            }
            shms.put(key, shm);
        }
        return shm.forOwner(owner.pid());
    }

    @Override
    public synchronized Optional<Shm> findShm(Agent owner, String id) {
        requireIpc(owner);
        String namespaceId = owner.namespaceId();
        if (id == null) {
            return Optional.empty();
        }
        String key = key(namespaceId, id);
        ShmImpl shm = shms.get(key);
        if (shm == null && store != null && store.exists(namespaceId, id)) {
            shm = new ShmImpl(id, namespaceId, 0L, store, store.load(namespaceId, id));
            shms.put(key, shm);
        }
        return Optional.ofNullable(shm).map(found -> found.forOwner(owner.pid()));
    }

    @Override
    public synchronized List<String> shms(String namespaceId) {
        Set<String> ids = new LinkedHashSet<>();
        if (store != null) {
            ids.addAll(store.list(namespaceId));
        }
        String prefix = namespaceId + "\u0000";
        for (String key : shms.keySet()) {
            if (key.startsWith(prefix)) {
                ids.add(key.substring(prefix.length()));
            }
        }
        return List.copyOf(new ArrayList<>(ids));
    }

    @Override
    public synchronized boolean removeShm(String namespaceId, String id) {
        ShmImpl shm = shms.remove(key(namespaceId, id));
        if (shm != null) {
            shm.deletePersisted();
            shm.close();
            LOG.info("removed shm {}/{}", namespaceId, id);
            return true;
        }
        if (store != null && store.delete(namespaceId, id)) {
            LOG.info("removed persisted shm {}/{}", namespaceId, id);
            return true;
        }
        return false;
    }

    @Override
    public synchronized Channel createChannel(Agent owner, int capacity) {
        requireIpc(owner);
        String id = "chan-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ChannelImpl channel = new ChannelImpl(id, capacity);
        channels.put(id, channel);
        owned.computeIfAbsent(owner.pid(), pid -> new LinkedHashSet<>()).add("channel:" + id);
        LOG.info("agent {} created channel {} (capacity {})", owner.pid(), id, capacity);
        return channel;
    }

    @Override
    public synchronized Optional<Channel> channel(String id) {
        Channel channel = channels.get(id);
        return Optional.ofNullable(channel);
    }

    @Override
    public synchronized List<String> channels() {
        return List.copyOf(new ArrayList<>(channels.keySet()));
    }

    @Override
    public synchronized boolean removeChannel(String id) {
        ChannelImpl channel = channels.remove(id);
        if (channel == null) {
            return false;
        }
        channel.close();
        owned.values().forEach(set -> set.remove("channel:" + id));
        return true;
    }

    @Override
    public synchronized void closeOwned(long pid) {
        Set<String> handles = owned.remove(pid);
        if (handles == null) {
            return;
        }
        for (String handle : handles) {
            if (handle.startsWith("channel:")) {
                removeChannel(handle.substring("channel:".length()));
            }
        }
    }

    @Override
    public synchronized void close() {
        for (ShmImpl shm : shms.values()) {
            shm.shutdown();
        }
        for (ChannelImpl channel : channels.values()) {
            channel.close();
        }
        shms.clear();
        channels.clear();
        owned.clear();
        if (store != null) {
            store.close();
        }
    }

    private static String key(String namespaceId, String id) {
        return namespaceId + "\u0000" + id;
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("shm id must not be blank");
        }
    }

    private static void requireIpc(Agent owner) {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        if (!owner.capabilities().containsKind(CapKind.IPC)) {
            throw new SecurityException("agent " + owner.pid() + " does not hold the ipc capability");
        }
    }
}
