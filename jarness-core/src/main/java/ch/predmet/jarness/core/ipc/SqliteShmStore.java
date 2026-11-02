package ch.predmet.jarness.core.ipc;

import ch.predmet.jarness.api.ipc.Cell;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite write-through store for {@link ch.predmet.jarness.api.ipc.Shm} cells.
 *
 * <p>The in-memory map in {@link ShmImpl} is the read path; every mutation is also written here so
 * a runtime restart can reload the shared state. Values are stored as JSON, which keeps cells
 * inspectable from {@code sqlite3} and makes the on-disk format independent of Java classes.</p>
 */
final class SqliteShmStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteShmStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path database;
    private final Connection connection;

    private SqliteShmStore(Path database, Connection connection) {
        this.database = database;
        this.connection = connection;
    }

    static SqliteShmStore open(Path database) {
        try {
            Files.createDirectories(database.toAbsolutePath().getParent());
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            connection.setAutoCommit(true);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=FULL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS shm_boards(
                            namespace TEXT NOT NULL,
                            id TEXT NOT NULL,
                            owner_pid INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            PRIMARY KEY(namespace, id)
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS shm_cells(
                            namespace TEXT NOT NULL,
                            shm_id TEXT NOT NULL,
                            cell TEXT NOT NULL,
                            version INTEGER NOT NULL,
                            value_json TEXT NOT NULL,
                            updated_at INTEGER NOT NULL,
                            owner_pid INTEGER NOT NULL,
                            PRIMARY KEY(namespace, shm_id, cell),
                            FOREIGN KEY(namespace, shm_id)
                                REFERENCES shm_boards(namespace, id) ON DELETE CASCADE
                        )""");
            }
            LOG.info("shm store open at {}", database);
            return new SqliteShmStore(database, connection);
        } catch (Exception e) {
            throw new IllegalStateException("failed to open shm store at " + database, e);
        }
    }

    synchronized boolean exists(String namespace, String id) {
        String sql = "SELECT 1 FROM shm_boards WHERE namespace = ? AND id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to look up shm " + namespace + "/" + id, e);
        }
    }

    synchronized void register(String namespace, String id, long ownerPid) {
        String sql = "INSERT OR IGNORE INTO shm_boards(namespace, id, owner_pid, created_at)"
                + " VALUES(?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            ps.setLong(3, ownerPid);
            ps.setLong(4, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to register shm " + namespace + "/" + id, e);
        }
    }

    synchronized Map<String, Cell> load(String namespace, String id) {
        String sql = "SELECT cell, version, value_json, updated_at, owner_pid"
                + " FROM shm_cells WHERE namespace = ? AND shm_id = ?";
        Map<String, Cell> cells = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cells.put(rs.getString("cell"), new Cell(
                            rs.getString("cell"),
                            rs.getLong("version"),
                            readValue(rs.getString("value_json")),
                            Instant.ofEpochMilli(rs.getLong("updated_at")),
                            rs.getLong("owner_pid")));
                }
                return cells;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load shm " + namespace + "/" + id, e);
        }
    }

    synchronized void put(String namespace, String id, Cell cell) {
        String sql = "INSERT INTO shm_cells(namespace, shm_id, cell, version, value_json, updated_at, owner_pid)"
                + " VALUES(?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT(namespace, shm_id, cell) DO UPDATE SET version = excluded.version,"
                + " value_json = excluded.value_json, updated_at = excluded.updated_at,"
                + " owner_pid = excluded.owner_pid";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            ps.setString(3, cell.name());
            ps.setLong(4, cell.version());
            ps.setString(5, writeValue(cell.value()));
            ps.setLong(6, cell.updatedAt().toEpochMilli());
            ps.setLong(7, cell.ownerPid());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to persist shm cell " + namespace + "/" + id
                    + "/" + cell.name(), e);
        }
    }

    synchronized void deleteCell(String namespace, String id, String cell) {
        String sql = "DELETE FROM shm_cells WHERE namespace = ? AND shm_id = ? AND cell = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            ps.setString(3, cell);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete shm cell " + namespace + "/" + id
                    + "/" + cell, e);
        }
    }

    synchronized boolean delete(String namespace, String id) {
        String sql = "DELETE FROM shm_boards WHERE namespace = ? AND id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, namespace);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete shm " + namespace + "/" + id, e);
        }
    }

    synchronized java.util.List<String> list(String namespace) {
        String sql = "SELECT id FROM shm_boards WHERE namespace = ? ORDER BY created_at";
        java.util.List<String> ids = new java.util.ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list shms in " + namespace, e);
        }
    }

    private static String writeValue(Object value) {
        try {
            JsonNode node = MAPPER.valueToTree(value);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("shm cell values must be JSON-compatible: "
                    + e.getMessage(), e);
        }
    }

    private static Object readValue(String json) {
        try {
            return MAPPER.treeToValue(MAPPER.readTree(json), Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored shm cell is not valid JSON: " + json, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
            LOG.info("shm store closed ({})", database);
        } catch (SQLException e) {
            LOG.warn("error closing shm store {}", database, e);
        }
    }
}
