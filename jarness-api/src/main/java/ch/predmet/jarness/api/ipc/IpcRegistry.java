package ch.predmet.jarness.api.ipc;

import ch.predmet.jarness.api.agent.Agent;

import java.util.List;
import java.util.Optional;

/**
 * The runtime's collaboration primitive table.
 *
 * <p>Handles are capabilities: creating one requires the {@code IPC} capability, and closing the
 * owner's pid closes every handle it created. Boards and channels live in the daemon; cross-daemon
 * IPC is out of scope for the single-node runtime.</p>
 */
public interface IpcRegistry {

    /**
     * Create a blackboard owned by an agent.
     *
     * @param owner owner
     * @param id    board id
     * @return the board
     */
    Blackboard createBoard(Agent owner, String id);

    /** @return the board with this id, if any. */
    Optional<Blackboard> board(String id);

    /** @return every board id. */
    List<String> boards();

    /**
     * Remove a board.
     *
     * @param id board id
     * @return whether a board was removed
     */
    boolean removeBoard(String id);

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
     * Close every board and channel owned by a pid.
     *
     * @param pid owner pid
     */
    void closeOwned(long pid);

    /** Close every board and channel. */
    void close();
}
