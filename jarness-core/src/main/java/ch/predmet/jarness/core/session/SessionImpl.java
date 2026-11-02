package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.ImageMetadata;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Live {@link Session} backed by a {@link SessionStore}, with turn-level durability.
 *
 * <p>Messages appended while a turn is running are held in memory and become durable only when the
 * turn commits, together with the turn's steps and image metadata. A run that crashes mid-turn
 * leaves the session exactly as it was after the previous commit. Messages appended outside a turn
 * (for example when copying a fork's image) are written immediately.</p>
 */
public class SessionImpl implements Session {

    private final SessionStore store;
    private final String id;
    private final String agentId;
    private Path workspace;
    private final String templateId;
    private final Instant createdAt;
    private final Map<String, Object> metadata;
    private final List<Message> messages;
    private final List<TurnImpl> turns;
    private final List<Message> pendingMessages = new ArrayList<>();

    private TurnImpl activeTurn;
    private String title;
    private ModelRef model;
    private Instant updatedAt;

    /**
     * Assemble a session from stored state.
     *
     * @param store     owning store used for persistence
     * @param id        session id
     * @param agentId   owning agent id
     * @param title     session title
     * @param workspace agent working directory
     * @param templateId template id
     * @param model     model binding, or {@code null}
     * @param createdAt creation time
     * @param updatedAt last activity time
     * @param metadata  extension metadata
     * @param messages  conversation history in wire order
     * @param turns     turns in index order
     */
    public SessionImpl(SessionStore store, String id, String agentId, String title, Path workspace,
                       String templateId, ModelRef model, Instant createdAt, Instant updatedAt,
                       Map<String, Object> metadata, List<Message> messages, List<TurnImpl> turns) {
        this.store = Objects.requireNonNull(store, "store");
        this.id = Objects.requireNonNull(id, "id");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.title = title == null ? "" : title;
        this.workspace = workspace;
        this.templateId = templateId == null ? "" : templateId;
        this.model = model;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
        this.metadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        this.messages = new ArrayList<>(messages == null ? List.of() : messages);
        this.turns = new ArrayList<>(turns == null ? List.of() : turns);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public void rebindWorkspace(Path newWorkspace) {
        if (newWorkspace == null) {
            return;
        }
        this.workspace = newWorkspace.toAbsolutePath().normalize();
        touch();
        store.update(this);
    }

    @Override
    public String templateId() {
        return templateId;
    }

    @Override
    public ModelRef model() {
        return model;
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    @Override
    public List<Turn> turns() {
        return List.copyOf(turns);
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.copyOf(metadata);
    }

    @Override
    public int nextTurnIndex() {
        return turns.isEmpty() ? 0 : turns.get(turns.size() - 1).index() + 1;
    }

    @Override
    public void append(Message message) {
        Objects.requireNonNull(message, "message");
        messages.add(message);
        if (activeTurn != null && activeTurn.state() == TurnState.RUNNING) {
            pendingMessages.add(message);
        } else {
            store.saveMessage(this, message);
        }
        touch();
    }

    @Override
    public Turn startTurn(String input) {
        if (activeTurn != null && activeTurn.state() == TurnState.RUNNING) {
            throw new IllegalStateException("session " + id + " already has a running turn");
        }
        TurnImpl turn = new TurnImpl(null, id, nextTurnIndex(), input);
        turns.add(turn);
        activeTurn = turn;
        touch();
        return turn;
    }

    @Override
    public void saveTurn(Turn turn) {
        if (turn.state() == TurnState.RUNNING) {
            // in-flight state stays in memory; only a terminal turn is durable
            return;
        }
        commitTurn(turn, null);
    }

    @Override
    public void commitTurn(Turn turn, ImageMetadata image) {
        if (turn.state() == TurnState.RUNNING) {
            return;
        }
        store.commitTurn(this, turn, pendingMessages, image);
        pendingMessages.clear();
        if (activeTurn != null && activeTurn.id().equals(turn.id())) {
            activeTurn = null;
        }
        touch();
    }

    @Override
    public void rename(String title) {
        this.title = title == null ? "" : title;
        touch();
        if (activeTurn == null) {
            store.update(this);
        }
    }

    @Override
    public void setModel(ModelRef model) {
        this.model = model;
        touch();
        if (activeTurn == null) {
            store.update(this);
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "SessionImpl[id=" + id + ", agent=" + agentId + ", title=" + title + "]";
    }
}
