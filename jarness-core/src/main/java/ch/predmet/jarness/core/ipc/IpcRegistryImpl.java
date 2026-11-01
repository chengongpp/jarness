package ch.predmet.jarness.core.ipc;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.ipc.Blackboard;
import ch.predmet.jarness.api.ipc.Channel;
import ch.predmet.jarness.api.ipc.IpcRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link IpcRegistry}: owns handles, enforces the IPC capability on creation, and closes
 * an owner's handles when it exits.
 */
public final class IpcRegistryImpl implements IpcRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(IpcRegistryImpl.class);

    private final Map<String, BlackboardImpl> boards = new LinkedHashMap<>();
    private final Map<String, ChannelImpl> channels = new LinkedHashMap<>();
    private final Map<Long, Set<String>> owned = new LinkedHashMap<>();

    @Override
    public synchronized Blackboard createBoard(Agent owner, String id) {
        requireIpc(owner);
        if (boards.containsKey(id)) {
            throw new IllegalStateException("blackboard " + id + " already exists");
        }
        BlackboardImpl board = new BlackboardImpl(id, owner.pid());
        boards.put(id, board);
        owned.computeIfAbsent(owner.pid(), pid -> new LinkedHashSet<>()).add("board:" + id);
        LOG.info("agent {} created blackboard {}", owner.pid(), id);
        return board;
    }

    @Override
    public synchronized Optional<Blackboard> board(String id) {
        return Optional.ofNullable(boards.get(id));
    }

    @Override
    public synchronized List<String> boards() {
        return List.copyOf(new ArrayList<>(boards.keySet()));
    }

    @Override
    public synchronized boolean removeBoard(String id) {
        BlackboardImpl board = boards.remove(id);
        if (board == null) {
            return false;
        }
        board.close();
        owned.values().forEach(set -> set.remove("board:" + id));
        return true;
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
            if (handle.startsWith("board:")) {
                removeBoard(handle.substring("board:".length()));
            } else if (handle.startsWith("channel:")) {
                removeChannel(handle.substring("channel:".length()));
            }
        }
    }

    @Override
    public synchronized void close() {
        for (BlackboardImpl board : boards.values()) {
            board.close();
        }
        for (ChannelImpl channel : channels.values()) {
            channel.close();
        }
        boards.clear();
        channels.clear();
        owned.clear();
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
