package ch.predmet.jarness.api.session;

import ch.predmet.jarness.api.provider.ModelRef;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A durable conversation owned by one agent.
 *
 * <p>A session outlives the agent object that produced it: resuming means loading the session's
 * messages and continuing with a fresh agent instance. Its {@link #workspace()} is the *agent's*
 * working directory and is unrelated to the Jarness base directory.</p>
 */
public interface Session {

    /** @return session id. */
    String id();

    /** @return the agent that owns this session. */
    String agentId();

    /** @return a short human title, usually the first user input. */
    String title();

    /** @return the working directory tool calls resolve against. */
    Path workspace();

    /** @return the template the agent was created from. */
    String templateId();

    /** @return the model this session talks to, or {@code null} when unset. */
    ModelRef model();

    /** @return creation time. */
    Instant createdAt();

    /** @return last activity time. */
    Instant updatedAt();

    /** @return the conversation in wire order. */
    List<Message> messages();

    /** @return the turns of this session in order. */
    List<Turn> turns();

    /** @return extension metadata persisted alongside the session. */
    Map<String, Object> metadata();

    /** @return the number of turns started so far, used as the next turn index. */
    int nextTurnIndex();

    /**
     * Append a message to the session and persist it.
     *
     * @param message the message to append
     */
    void append(Message message);

    /**
     * Start a new turn for {@code input}.
     *
     * @param input the user input
     * @return the running turn
     */
    Turn startTurn(String input);

    /**
     * Persist the current state of a turn (state, steps, output).
     *
     * <p>A running turn is not durable: implementations may keep it in memory until
     * {@link #commitTurn(Turn, ImageMetadata)} is called for a terminal turn. This method remains
     * for API compatibility and for stores that persist incremental state.</p>
     *
     * @param turn the turn to persist
     */
    void saveTurn(Turn turn);

    /**
     * Commit a terminal turn and its image metadata atomically.
     *
     * @param turn  the terminal turn
     * @param image image metadata to persist, or {@code null}
     */
    default void commitTurn(Turn turn, ImageMetadata image) {
        saveTurn(turn);
    }

    /**
     * Overwrite the title.
     *
     * @param title the new title
     */
    void rename(String title);

    /**
     * Update the model bound to this session.
     *
     * @param model the new model reference
     */
    void setModel(ModelRef model);

    /** @return the last user message, or {@code null} when the session has none. */
    default Message lastUserMessage() {
        List<Message> messages = messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.get(i);
            }
        }
        return null;
    }

    /** @return whether the session holds no messages. */
    default boolean isEmpty() {
        return messages().isEmpty();
    }
}
