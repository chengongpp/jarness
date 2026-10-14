package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Role;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.SessionInit;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.session.SessionSummary;
import ch.predmet.jarness.api.session.Step;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed {@link SessionStore}.
 *
 * <p>One JDBC connection is kept open for the store's lifetime and every public method synchronizes
 * on it: one runtime, low contention, simplicity over pooling. The database runs in WAL mode with
 * foreign keys enforced; deleting a session cascades to its messages, turns, and steps. Instants
 * are stored as epoch millis, content blocks and metadata as JSON via {@link MessageCodec}.</p>
 *
 * <p>Typical URL: {@code jdbc:sqlite:/path/to/sessions.db}. Relative paths and
 * {@code jdbc:sqlite::memory:} are passed through to the driver untouched.</p>
 */
public class SqliteSessionStore implements SessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteSessionStore.class);
    private static final String FILE_PREFIX = "jdbc:sqlite:";

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
     * @param jdbcUrl the JDBC URL, e.g. {@code jdbc:sqlite:/path/sessions.db}
     */
    public SqliteSessionStore(String jdbcUrl) {
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
                st.execute("PRAGMA foreign_keys=ON");
            }
            createSchema(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open session store at " + jdbcUrl, e);
        }
        LOG.info("session store open at {}", jdbcUrl);
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
                    CREATE TABLE IF NOT EXISTS sessions(
                        id TEXT PRIMARY KEY,
                        agent_id TEXT NOT NULL,
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
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        seq INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, seq)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS turns(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        idx INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        input TEXT NOT NULL DEFAULT '',
                        output TEXT NOT NULL DEFAULT '',
                        error TEXT,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER
                    )""");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_turns_session ON turns(session_id, idx)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS steps(
                        turn_id TEXT NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
                        idx INTEGER NOT NULL,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER,
                        finish_reason TEXT,
                        prompt_tokens INTEGER NOT NULL DEFAULT 0,
                        completion_tokens INTEGER NOT NULL DEFAULT 0,
                        tool_calls INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (turn_id, idx)
                    )""");
        }
    }

    @Override
    public synchronized Session create(SessionInit init) {
        checkOpen();
        String id = init.id() == null || init.id().isBlank() ? UUID.randomUUID().toString() : init.id();
        Instant now = Instant.now();
        String title = init.title() == null ? "" : init.title();
        String workspace = init.workspace() == null ? "" : init.workspace().toString();
        String templateId = init.templateId() == null ? "" : init.templateId();
        String sql = "INSERT INTO sessions(id, agent_id, title, workspace, template_id,"
                + " model_provider, model_id, created_at, updated_at, metadata)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, init.agentId());
            ps.setString(3, title);
            ps.setString(4, workspace);
            ps.setString(5, templateId);
            if (init.model() == null) {
                ps.setNull(6, java.sql.Types.VARCHAR);
                ps.setNull(7, java.sql.Types.VARCHAR);
            } else {
                ps.setString(6, init.model().providerId());
                ps.setString(7, init.model().modelId());
            }
            ps.setLong(8, now.toEpochMilli());
            ps.setLong(9, now.toEpochMilli());
            ps.setString(10, MessageCodec.writeMetadata(init.metadata()));
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
                List<Message> messages = loadMessages(id);
                List<TurnImpl> turns = loadTurns(id);
                return Optional.of(new SessionImpl(this, id, agentId, title,
                        workspace == null || workspace.isEmpty() ? null : Path.of(workspace),
                        templateId, model, createdAt, updatedAt, metadata, messages, turns));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load session " + id, e);
        }
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

    private List<TurnImpl> loadTurns(String sessionId) throws SQLException {
        String sql = "SELECT id, idx, state, input, output, error, started_at, ended_at"
                + " FROM turns WHERE session_id = ? ORDER BY idx";
        List<TurnImpl> turns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String turnId = rs.getString("id");
                    long ended = rs.getLong("ended_at");
                    turns.add(TurnImpl.restore(
                            turnId,
                            sessionId,
                            rs.getInt("idx"),
                            TurnState.valueOf(rs.getString("state")),
                            rs.getString("input"),
                            rs.getString("output"),
                            rs.getString("error"),
                            Instant.ofEpochMilli(rs.getLong("started_at")),
                            rs.wasNull() ? null : Instant.ofEpochMilli(ended),
                            loadSteps(turnId)));
                }
            }
        }
        return turns;
    }

    private List<Step> loadSteps(String turnId) throws SQLException {
        String sql = "SELECT idx, started_at, ended_at, finish_reason, prompt_tokens,"
                + " completion_tokens, tool_calls FROM steps WHERE turn_id = ? ORDER BY idx";
        List<Step> steps = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, turnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ended = rs.getLong("ended_at");
                    steps.add(new Step(
                            rs.getInt("idx"),
                            Instant.ofEpochMilli(rs.getLong("started_at")),
                            rs.wasNull() ? null : Instant.ofEpochMilli(ended),
                            rs.getString("finish_reason"),
                            rs.getInt("prompt_tokens"),
                            rs.getInt("completion_tokens"),
                            rs.getInt("tool_calls")));
                }
            }
        }
        return steps;
    }

    @Override
    public synchronized List<SessionSummary> list(int limit) {
        checkOpen();
        String sql = summariesSql("")
                + " ORDER BY s.updated_at DESC LIMIT ?";
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
        String sql = summariesSql(" WHERE s.agent_id = ?")
                + " ORDER BY s.updated_at DESC";
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
    public synchronized void saveMessage(Session session, Message message) {
        checkOpen();
        String sql = "INSERT INTO messages(id, session_id, seq, role, content, at)"
                + " VALUES(?,?,?,?,?,?)";
        try {
            int seq = nextMessageSeq(session.id());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, message.id());
                ps.setString(2, session.id());
                ps.setInt(3, seq);
                ps.setString(4, message.role().name());
                ps.setString(5, MessageCodec.writeBlocks(message.content()));
                ps.setLong(6, message.at().toEpochMilli());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save message " + message.id(), e);
        }
    }

    private int nextMessageSeq(String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
    }

    @Override
    public synchronized void saveTurn(Session session, Turn turn) {
        checkOpen();
        String turnSql = "INSERT OR REPLACE INTO turns"
                + "(id, session_id, idx, state, input, output, error, started_at, ended_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?)";
        String stepSql = "INSERT OR REPLACE INTO steps"
                + "(turn_id, idx, started_at, ended_at, finish_reason, prompt_tokens,"
                + " completion_tokens, tool_calls) VALUES(?,?,?,?,?,?,?,?)";
        try {
            try (PreparedStatement ps = connection.prepareStatement(turnSql)) {
                ps.setString(1, turn.id());
                ps.setString(2, session.id());
                ps.setInt(3, turn.index());
                ps.setString(4, turn.state().name());
                ps.setString(5, turn.input());
                ps.setString(6, turn.output());
                if (turn.error() == null) {
                    ps.setNull(7, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(7, turn.error());
                }
                ps.setLong(8, turn.startedAt().toEpochMilli());
                if (turn.endedAt() == null) {
                    ps.setNull(9, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(9, turn.endedAt().toEpochMilli());
                }
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(stepSql)) {
                for (Step step : turn.steps()) {
                    ps.setString(1, turn.id());
                    ps.setInt(2, step.index());
                    ps.setLong(3, step.startedAt().toEpochMilli());
                    if (step.endedAt() == null) {
                        ps.setNull(4, java.sql.Types.INTEGER);
                    } else {
                        ps.setLong(4, step.endedAt().toEpochMilli());
                    }
                    if (step.finishReason() == null) {
                        ps.setNull(5, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(5, step.finishReason());
                    }
                    ps.setInt(6, step.promptTokens());
                    ps.setInt(7, step.completionTokens());
                    ps.setInt(8, step.toolCalls());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to save turn " + turn.id(), e);
        }
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
                ps.setNull(2, java.sql.Types.VARCHAR);
                ps.setNull(3, java.sql.Types.VARCHAR);
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
            LOG.info("session store closed ({})", jdbcUrl);
        } catch (SQLException e) {
            LOG.warn("error closing session store {}: {}", jdbcUrl, e.toString());
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("session store is closed");
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
}
