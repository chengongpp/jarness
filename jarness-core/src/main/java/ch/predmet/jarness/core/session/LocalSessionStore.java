package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.EventRecord;
import ch.predmet.jarness.api.session.ImageMetadata;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Role;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.SessionInit;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.session.SessionSummary;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed {@link SessionStore}: the default {@code LocalSessionStore}.
 *
 * <p>The store is an image store with a turn-level durability boundary. A turn is written in one
 * transaction together with its messages, steps (inside the turn delta), events, and image
 * metadata; a turn that never commits leaves nothing behind. Checkpoints compact the covered
 * deltas into a full snapshot that is replayed on load.</p>
 *
 * <p>Database: {@code <base>/data/jarness.db}, WAL mode and {@code synchronous=FULL} so a committed
 * turn survives a crash. One connection is kept open and all public methods synchronize on it: one
 * runtime, low contention, simplicity over pooling.</p>
 */
public class LocalSessionStore implements SessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSessionStore.class);
    private static final String FILE_PREFIX = "jdbc:sqlite:";
    private static final String SCHEMA_VERSION = "1";

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOG.warn("sqlite-jdbc driver not found on classpath: {}", e.toString());
        }
    }

    private final String jdbcUrl;
    private final Connection connection;
    private boolean closed;

    /**
     * Open (or create) the store.
     *
     * @param jdbcUrl the JDBC URL, e.g. {@code jdbc:sqlite:/path/jarness.db}
     */
    public LocalSessionStore(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        this.jdbcUrl = jdbcUrl;
        ensureParentDirectory(jdbcUrl);
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            this.connection.setAutoCommit(true);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            createSchema(connection);
            writeSchemaVersion(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open image store at " + jdbcUrl, e);
        }
        LOG.info("image store open at {}", jdbcUrl);
    }

    private static void ensureParentDirectory(String jdbcUrl) {
        if (!jdbcUrl.startsWith(FILE_PREFIX)) {
            return;
        }
        String location = jdbcUrl.substring(FILE_PREFIX.length());
        if (location.isBlank() || location.equals(":memory:")) {
            return;
        }
        try {
            Path parent = Path.of(location).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            LOG.warn("could not create parent directory for {}: {}", jdbcUrl, e.toString());
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS meta(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions(
                        id TEXT PRIMARY KEY,
                        agent_id TEXT NOT NULL,
                        namespace TEXT NOT NULL DEFAULT 'default',
                        title TEXT NOT NULL DEFAULT '',
                        workspace TEXT NOT NULL DEFAULT '',
                        template_id TEXT NOT NULL DEFAULT '',
                        model_provider TEXT,
                        model_id TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        metadata TEXT NOT NULL DEFAULT '{}'
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS messages(
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        id TEXT NOT NULL UNIQUE,
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        turn_seq INTEGER,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, seq)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS turns(
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        seq INTEGER NOT NULL,
                        id TEXT NOT NULL UNIQUE,
                        status TEXT NOT NULL,
                        delta TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER,
                        PRIMARY KEY(session_id, seq)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS events(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        turn_seq INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_session ON events(session_id, turn_seq)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS checkpoints(
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        seq INTEGER NOT NULL,
                        image TEXT NOT NULL,
                        at INTEGER NOT NULL,
                        PRIMARY KEY(session_id, seq)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_image(
                        session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
                        agent_id TEXT NOT NULL,
                        namespace TEXT NOT NULL,
                        schema_ver INTEGER NOT NULL,
                        checkpoint_turn INTEGER NOT NULL DEFAULT -1,
                        deps TEXT NOT NULL DEFAULT '{}',
                        budget TEXT NOT NULL,
                        caps TEXT NOT NULL,
                        state TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
        }
    }

    private static void writeSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta(key, value) VALUES('schema_version', ?) ON CONFLICT(key) DO UPDATE SET value=?")) {
            ps.setString(1, SCHEMA_VERSION);
            ps.setString(2, SCHEMA_VERSION);
            ps.executeUpdate();
        }
    }

    private int schemaVersion() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key='schema_version'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- sessions

    @Override
    public synchronized Session create(SessionInit init) {
        checkOpen();
        String id = init.id() == null || init.id().isBlank() ? UUID.randomUUID().toString() : init.id();
        Instant now = Instant.now();
        String title = init.title() == null ? "" : init.title();
        String workspace = init.workspace() == null ? "" : init.workspace().toString();
        String templateId = init.templateId() == null ? "" : init.templateId();
        String namespace = stringMetadata(init.metadata(), "namespace", "default");
        String sql = "INSERT INTO sessions(id, agent_id, namespace, title, workspace, template_id,"
                + " model_provider, model_id, created_at, updated_at, metadata)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, init.agentId());
            ps.setString(3, namespace);
            ps.setString(4, title);
            ps.setString(5, workspace);
            ps.setString(6, templateId);
            if (init.model() == null) {
                ps.setNull(7, Types.VARCHAR);
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(7, init.model().providerId());
                ps.setString(8, init.model().modelId());
            }
            ps.setLong(9, now.toEpochMilli());
            ps.setLong(10, now.toEpochMilli());
            ps.setString(11, MessageCodec.writeMetadata(init.metadata()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create session " + id, e);
        }
        LOG.debug("created session {} for agent {}", id, init.agentId());
        return new SessionImpl(this, id, init.agentId(), title,
                workspace.isEmpty() ? null : Path.of(workspace), templateId, init.model(),
                now, now, init.metadata(), List.of(), List.of());
    }

    @Override
    public synchronized Optional<Session> load(String id) {
        checkOpen();
        String sql = "SELECT agent_id, title, workspace, template_id, model_provider, model_id,"
                + " created_at, updated_at, metadata FROM sessions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String agentId = rs.getString("agent_id");
                String title = rs.getString("title");
                String workspace = rs.getString("workspace");
                String templateId = rs.getString("template_id");
                ModelRef model = readModel(rs);
                Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
                Instant updatedAt = Instant.ofEpochMilli(rs.getLong("updated_at"));
                Map<String, Object> metadata = MessageCodec.readMetadata(rs.getString("metadata"));
                History history = loadHistory(id);
                return Optional.of(new SessionImpl(this, id, agentId, title,
                        workspace == null || workspace.isEmpty() ? null : Path.of(workspace),
                        templateId, model, createdAt, updatedAt, metadata, history.messages(), history.turns()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load session " + id, e);
        }
    }

    /** Messages and turns reconstructed from the checkpoint and/or committed deltas. */
    private record History(List<Message> messages, List<TurnImpl> turns) {
    }

    private History loadHistory(String sessionId) throws SQLException {
        Integer checkpointSeq = latestCheckpointSeq(sessionId);
        if (checkpointSeq != null) {
            ImageCodec.Snapshot snapshot = readCheckpoint(sessionId, checkpointSeq);
            if (snapshot != null) {
                List<Message> messages = new ArrayList<>(snapshot.messages());
                List<TurnImpl> turns = new ArrayList<>(snapshot.turns());
                for (TurnDeltaRow row : turnRows(sessionId, checkpointSeq)) {
                    ImageCodec.TurnDelta delta = ImageCodec.readTurn(sessionId, row.delta());
                    if (delta == null) {
                        LOG.warn("undecodable turn {} in session {}; skipping", row.seq(), sessionId);
                        continue;
                    }
                    turns.add(delta.turn());
                    messages.addAll(delta.messages());
                }
                return new History(messages, turns);
            }
            LOG.warn("checkpoint {} of session {} is unreadable; falling back to the delta log",
                    checkpointSeq, sessionId);
        }
        List<Message> messages = loadMessages(sessionId);
        List<TurnImpl> turns = new ArrayList<>();
        for (TurnDeltaRow row : turnRows(sessionId, -1)) {
            ImageCodec.TurnDelta delta = ImageCodec.readTurn(sessionId, row.delta());
            if (delta != null) {
                turns.add(delta.turn());
            }
        }
        return new History(messages, turns);
    }

    private record TurnDeltaRow(int seq, String id, String status, String delta) {
    }

    private List<TurnDeltaRow> turnRows(String sessionId, int afterSeq) throws SQLException {
        String sql = "SELECT seq, id, status, delta FROM turns WHERE session_id = ? AND seq > ? ORDER BY seq";
        List<TurnDeltaRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, afterSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TurnDeltaRow(rs.getInt("seq"), rs.getString("id"),
                            rs.getString("status"), rs.getString("delta")));
                }
            }
        }
        return rows;
    }

    private List<Message> loadMessages(String sessionId) throws SQLException {
        String sql = "SELECT id, role, content, at FROM messages WHERE session_id = ? ORDER BY seq";
        List<Message> messages = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(new Message(
                            rs.getString("id"),
                            Role.valueOf(rs.getString("role")),
                            MessageCodec.readBlocks(rs.getString("content")),
                            Instant.ofEpochMilli(rs.getLong("at"))));
                }
            }
        }
        return messages;
    }

    private Integer latestCheckpointSeq(String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(seq) FROM checkpoints WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int value = rs.getInt(1);
                    return rs.wasNull() ? null : value;
                }
            }
        }
        return null;
    }

    private ImageCodec.Snapshot readCheckpoint(String sessionId, int seq) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT image FROM checkpoints WHERE session_id = ? AND seq = ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? ImageCodec.readSnapshot(sessionId, rs.getString(1)) : null;
            }
        }
    }

    @Override
    public synchronized void saveMessage(Session session, Message message) {
        checkOpen();
        String sql = "INSERT INTO messages(id, session_id, turn_seq, role, content, at)"
                + " VALUES(?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, message.id());
            ps.setString(2, session.id());
            ps.setNull(3, Types.INTEGER);
            ps.setString(4, message.role().name());
            ps.setString(5, MessageCodec.writeBlocks(message.content()));
            ps.setLong(6, message.at().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save message " + message.id(), e);
        }
    }

    @Override
    public synchronized void commitTurn(Session session, Turn turn, List<Message> newMessages,
                                        ImageMetadata image) {
        checkOpen();
        if (turn.state() == TurnState.RUNNING) {
            throw new IllegalArgumentException("cannot commit a running turn " + turn.id());
        }
        List<Message> messages = newMessages == null ? List.of() : newMessages;
        String delta = ImageCodec.writeTurn(turn, messages);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO turns(session_id, seq, id, status, delta, started_at, ended_at)"
                            + " VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, session.id());
                ps.setInt(2, turn.index());
                ps.setString(3, turn.id());
                ps.setString(4, "committed");
                ps.setString(5, delta);
                ps.setLong(6, turn.startedAt().toEpochMilli());
                if (turn.endedAt() == null) {
                    ps.setNull(7, Types.INTEGER);
                } else {
                    ps.setLong(7, turn.endedAt().toEpochMilli());
                }
                ps.executeUpdate();
            }
            for (Message message : messages) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO messages(id, session_id, turn_seq, role, content, at)"
                                + " VALUES(?,?,?,?,?,?)")) {
                    ps.setString(1, message.id());
                    ps.setString(2, session.id());
                    ps.setInt(3, turn.index());
                    ps.setString(4, message.role().name());
                    ps.setString(5, MessageCodec.writeBlocks(message.content()));
                    ps.setLong(6, message.at().toEpochMilli());
                    ps.executeUpdate();
                }
            }
            appendEvent(session.id(), turn.index(), "turn.committed", Map.of(
                    "turnId", turn.id(),
                    "state", turn.state().name(),
                    "input", turn.input(),
                    "steps", turn.steps().size(),
                    "promptTokens", turn.promptTokens(),
                    "completionTokens", turn.completionTokens()), turn.endedAt());
            for (Message message : messages) {
                appendEvent(session.id(), turn.index(), "message.appended", Map.of(
                        "messageId", message.id(),
                        "role", message.role().name(),
                        "blocks", message.content().size()), message.at());
            }
            if (image != null) {
                writeImage(session.id(), image);
            }
            touchSession(session);
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("failed to commit turn " + turn.id(), e);
        } finally {
            restoreAutoCommit();
        }
        LOG.debug("committed turn {}/{} of session {} ({} message(s))",
                turn.index(), turn.id(), session.id(), messages.size());
    }

    private void appendEvent(String sessionId, int turnSeq, String type, Map<String, Object> payload,
                             Instant at) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO events(id, session_id, turn_seq, type, payload, at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, sessionId);
            ps.setInt(3, turnSeq);
            ps.setString(4, type);
            ps.setString(5, MessageCodec.writeMetadata(payload));
            ps.setLong(6, (at == null ? Instant.now() : at).toEpochMilli());
            ps.executeUpdate();
        }
    }

    private void touchSession(Session session) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET updated_at = ?, title = ?, model_provider = ?, model_id = ? WHERE id = ?")) {
            ps.setLong(1, session.updatedAt().toEpochMilli());
            ps.setString(2, session.title());
            ModelRef model = session.model();
            if (model == null) {
                ps.setNull(3, Types.VARCHAR);
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(3, model.providerId());
                ps.setString(4, model.modelId());
            }
            ps.setString(5, session.id());
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void saveTurn(Session session, Turn turn) {
        checkOpen();
        if (turn.state() == TurnState.RUNNING) {
            return;
        }
        commitTurn(session, turn, List.of(), null);
    }

    @Override
    public synchronized Optional<ImageMetadata> image(String sessionId) {
        checkOpen();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT agent_id, namespace, schema_ver, checkpoint_turn, deps, budget, caps, state, updated_at"
                        + " FROM agent_image WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Map<String, Object> deps = MessageCodec.readMetadata(rs.getString("deps"));
                Budget budget = decodeBudget(rs.getString("budget"));
                var caps = ImageCodec.capsFromJson(rs.getString("caps"));
                return Optional.of(new ImageMetadata(
                        rs.getString("agent_id"),
                        sessionId,
                        rs.getString("namespace"),
                        rs.getInt("schema_ver"),
                        rs.getInt("checkpoint_turn"),
                        deps, budget, caps, rs.getString("state"),
                        Instant.ofEpochMilli(rs.getLong("updated_at"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read image of session " + sessionId, e);
        }
    }

    private static Budget decodeBudget(String json) {
        if (json == null || json.isBlank()) {
            return Budget.unlimited();
        }
        return ImageCodec.readImage("{\"budget\":" + json + "}").budget();
    }

    @Override
    public synchronized void saveImage(ImageMetadata image) {
        checkOpen();
        try {
            connection.setAutoCommit(false);
            writeImage(image.sessionId(), image);
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("failed to save image of session " + image.sessionId(), e);
        } finally {
            restoreAutoCommit();
        }
    }

    private void writeImage(String sessionId, ImageMetadata image) throws SQLException {
        String sql = "INSERT INTO agent_image(session_id, agent_id, namespace, schema_ver, checkpoint_turn,"
                + " deps, budget, caps, state, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(session_id) DO UPDATE SET agent_id=excluded.agent_id,"
                + " namespace=excluded.namespace, schema_ver=excluded.schema_ver,"
                + " checkpoint_turn=CASE WHEN excluded.checkpoint_turn < 0 THEN agent_image.checkpoint_turn"
                + " ELSE excluded.checkpoint_turn END, deps=excluded.deps,"
                + " budget=excluded.budget, caps=excluded.caps, state=excluded.state,"
                + " updated_at=excluded.updated_at";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, image.agentId());
            ps.setString(3, image.namespace());
            ps.setInt(4, image.schemaVersion());
            ps.setInt(5, image.checkpointTurn());
            ps.setString(6, MessageCodec.writeMetadata(image.deps()));
            ps.setString(7, budgetJson(image.budget()));
            ps.setString(8, capsJson(image.capabilities()));
            ps.setString(9, image.state());
            ps.setLong(10, image.updatedAt().toEpochMilli());
            ps.executeUpdate();
        }
    }

    private static String budgetJson(Budget budget) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tokensLeft", budget.tokensLeft());
        map.put("deadline", budget.deadline() == null ? null : budget.deadline().toString());
        map.put("toolSlots", budget.toolSlots());
        map.put("maxSpawnDepth", budget.maxSpawnDepth());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode budget", e);
        }
    }

    private static String capsJson(ch.predmet.jarness.api.capability.CapabilitySet caps) {
        return ImageCodec.capsJson(caps);
    }

    @Override
    public synchronized void checkpoint(Session session, ImageMetadata image) {
        checkOpen();
        Optional<Session> full = load(session.id());
        if (full.isEmpty()) {
            return;
        }
        int seq = full.get().turns().stream().mapToInt(Turn::index).max().orElse(0);
        String snapshot = ImageCodec.writeSnapshot(full.get());
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO checkpoints(session_id, seq, image, at) VALUES(?,?,?,?)")) {
                ps.setString(1, session.id());
                ps.setInt(2, seq);
                ps.setString(3, snapshot);
                ps.setLong(4, Instant.now().toEpochMilli());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM messages WHERE session_id = ? AND turn_seq IS NOT NULL AND turn_seq <= ?")) {
                ps.setString(1, session.id());
                ps.setInt(2, seq);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM turns WHERE session_id = ? AND seq <= ?")) {
                ps.setString(1, session.id());
                ps.setInt(2, seq);
                ps.executeUpdate();
            }
            if (image != null) {
                writeImage(session.id(), image);
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE agent_image SET checkpoint_turn = ?, updated_at = ? WHERE session_id = ?")) {
                ps.setInt(1, seq);
                ps.setLong(2, Instant.now().toEpochMilli());
                ps.setString(3, session.id());
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("failed to checkpoint session " + session.id(), e);
        } finally {
            restoreAutoCommit();
        }
        LOG.info("checkpointed session {} at turn {} ({} turn(s) compacted)", session.id(), seq, seq);
    }

    @Override
    public synchronized List<EventRecord> events(String sessionId, int fromTurn) {
        checkOpen();
        String sql = "SELECT id, turn_seq, type, payload, at FROM events WHERE session_id = ? AND turn_seq >= ?"
                + " ORDER BY at, rowid";
        List<EventRecord> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, Math.max(0, fromTurn));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new EventRecord(
                            rs.getString("id"), sessionId, rs.getInt("turn_seq"),
                            rs.getString("type"), MessageCodec.readMetadata(rs.getString("payload")),
                            Instant.ofEpochMilli(rs.getLong("at"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read events of session " + sessionId, e);
        }
        return events;
    }

    @Override
    public synchronized void update(Session session) {
        checkOpen();
        String sql = "UPDATE sessions SET title = ?, model_provider = ?, model_id = ?,"
                + " updated_at = ?, metadata = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, session.title());
            ModelRef model = session.model();
            if (model == null) {
                ps.setNull(2, Types.VARCHAR);
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(2, model.providerId());
                ps.setString(3, model.modelId());
            }
            ps.setLong(4, session.updatedAt().toEpochMilli());
            ps.setString(5, MessageCodec.writeMetadata(session.metadata()));
            ps.setString(6, session.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update session " + session.id(), e);
        }
    }

    @Override
    public synchronized List<SessionSummary> list(int limit) {
        checkOpen();
        String sql = summariesSql("") + " ORDER BY s.updated_at DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, limit));
            return readSummaries(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list sessions", e);
        }
    }

    @Override
    public synchronized List<SessionSummary> listByAgent(String agentId) {
        checkOpen();
        String sql = summariesSql(" WHERE s.agent_id = ?") + " ORDER BY s.updated_at DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, agentId);
            return readSummaries(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list sessions of agent " + agentId, e);
        }
    }

    private static String summariesSql(String where) {
        return "SELECT s.id, s.agent_id, s.title, s.template_id, s.created_at, s.updated_at,"
                + " (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS message_count,"
                + " (SELECT COUNT(*) FROM turns t WHERE t.session_id = s.id) AS turn_count"
                + " FROM sessions s" + where;
    }

    private static List<SessionSummary> readSummaries(PreparedStatement ps) throws SQLException {
        List<SessionSummary> summaries = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                summaries.add(new SessionSummary(
                        rs.getString("id"),
                        rs.getString("agent_id"),
                        rs.getString("title"),
                        rs.getString("template_id"),
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        Instant.ofEpochMilli(rs.getLong("updated_at")),
                        rs.getInt("message_count"),
                        rs.getInt("turn_count")));
            }
        }
        return summaries;
    }

    @Override
    public synchronized boolean delete(String id) {
        checkOpen();
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
            ps.setString(1, id);
            boolean removed = ps.executeUpdate() > 0;
            if (removed) {
                LOG.debug("deleted session {}", id);
            }
            return removed;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete session " + id, e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
            LOG.info("image store closed ({})", jdbcUrl);
        } catch (SQLException e) {
            LOG.warn("error closing image store {}: {}", jdbcUrl, e.toString());
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("session store is closed");
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.warn("rollback failed: {}", e.toString());
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            LOG.warn("could not restore auto-commit: {}", e.toString());
        }
    }

    private static ModelRef readModel(ResultSet rs) throws SQLException {
        String provider = rs.getString("model_provider");
        String modelId = rs.getString("model_id");
        if (modelId == null && provider == null) {
            return null;
        }
        return new ModelRef(provider, modelId);
    }

    private static String stringMetadata(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    /** @return the on-disk schema version. */
    public int schemaVersionNumber() {
        return schemaVersion();
    }
}
