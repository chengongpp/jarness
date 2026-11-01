package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.EventRecord;
import ch.predmet.jarness.api.session.ImageMetadata;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.SessionInit;
import ch.predmet.jarness.api.session.Step;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transaction, checkpoint, replay, and image-metadata tests for {@link LocalSessionStore}.
 */
class LocalSessionStoreTest {

    @TempDir
    Path dir;

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("jarness.db");
    }

    private Session session(LocalSessionStore store) {
        return store.create(new SessionInit(null, "agent-1", null, dir, "standard", null, Map.of()));
    }

    @Test
    void runningTurnIsNotDurableUntilCommit() {
        String sessionId;
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session session = session(store);
            sessionId = session.id();
            Turn turn = session.startTurn("hello");
            session.append(Message.user("hello"));
            session.append(Message.system("working"));
            step(turn, 0, "tool_calls", 10, 5);
            turn.finish(TurnState.COMPLETED, "done", null);

            try (LocalSessionStore reader = new LocalSessionStore(url())) {
                Session before = reader.load(sessionId).orElseThrow();
                assertTrue(before.messages().isEmpty(), "in-flight messages must not be visible");
                assertTrue(before.turns().isEmpty(), "in-flight turns must not be visible");
            }

            session.commitTurn(turn, null);
        }
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session resumed = store.load(sessionId).orElseThrow();
            assertEquals(2, resumed.messages().size());
            assertEquals(1, resumed.turns().size());
            assertEquals(TurnState.COMPLETED, resumed.turns().get(0).state());
            assertEquals(1, resumed.turns().get(0).steps().size());
            assertEquals(15, resumed.turns().get(0).promptTokens() + resumed.turns().get(0).completionTokens());
            assertTrue(store.events(sessionId, 0).size() >= 2, "turn + message events were written");
        }
    }

    @Test
    void uncommittedTurnDisappearsAfterCrash() {
        String sessionId;
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session session = session(store);
            sessionId = session.id();
            Turn turn = session.startTurn("lost");
            session.append(Message.user("lost"));
            turn.finish(TurnState.FAILED, "", "boom");
            // no commit: simulate a crash between finish and the durability boundary
        }
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session resumed = store.load(sessionId).orElseThrow();
            assertTrue(resumed.messages().isEmpty());
            assertTrue(resumed.turns().isEmpty());
        }
    }

    @Test
    void checkpointCompactsThenReplaysLaterTurns() throws Exception {
        String sessionId;
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session session = session(store);
            sessionId = session.id();
            for (int i = 0; i < 2; i++) {
                Turn turn = session.startTurn("q" + i);
                session.append(Message.user("q" + i));
                session.append(Message.system("a" + i));
                step(turn, 0, "stop", 4, 2);
                turn.finish(TurnState.COMPLETED, "a" + i, null);
                session.commitTurn(turn, null);
            }
            store.checkpoint(session, null);

            try (Connection c = DriverManager.getConnection(url());
                 Statement st = c.createStatement()) {
                assertEquals(0, count(st, "SELECT COUNT(*) FROM turns"));
                assertEquals(0, count(st, "SELECT COUNT(*) FROM messages WHERE turn_seq IS NOT NULL"));
                assertEquals(1, count(st, "SELECT COUNT(*) FROM checkpoints"));
            }

            Session resumed = store.load(sessionId).orElseThrow();
            assertEquals(4, resumed.messages().size());
            assertEquals(2, resumed.turns().size());

            Turn third = resumed.startTurn("q2");
            resumed.append(Message.user("q2"));
            step(third, 0, "stop", 3, 1);
            third.finish(TurnState.COMPLETED, "a2", null);
            resumed.commitTurn(third, null);

            Session after = store.load(sessionId).orElseThrow();
            assertEquals(5, after.messages().size());
            assertEquals(3, after.turns().size());
            assertEquals("q2", after.turns().get(2).input());
        }
    }

    @Test
    void imageMetadataRoundTripsBudgetAndCapabilities() {
        String sessionId;
        Instant deadline = Instant.parse("2030-01-01T00:00:00Z");
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session session = session(store);
            sessionId = session.id();
            ImageMetadata image = new ImageMetadata("agent-7", sessionId, "default",
                    ImageMetadata.SCHEMA_VERSION, -1,
                    Map.of("template", Map.of("id", "standard", "version", "1"),
                            "tools", List.of("read", "bash")),
                    Budget.of(123, deadline, 4, 2),
                    CapabilitySet.of(
                            new Capability("c1", CapKind.FS_READ, "/workspace/*", null),
                            new Capability("c2", CapKind.EXEC, "*", null)),
                    "IDLE", Instant.now());
            store.saveImage(image);

            ImageMetadata back = store.image(sessionId).orElseThrow();
            assertEquals("agent-7", back.agentId());
            assertEquals(123, back.budget().tokensLeft());
            assertEquals(deadline, back.budget().deadline());
            assertEquals(4, back.budget().toolSlots());
            assertEquals(2, back.budget().maxSpawnDepth());
            assertEquals(2, back.capabilities().size());
            assertTrue(back.capabilities().containsKind(CapKind.FS_READ));
            assertEquals("standard", ((Map<?, ?>) back.deps().get("template")).get("id"));
        }
    }

    @Test
    void nextTurnIndexContinuesAfterCompaction() {
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session session = session(store);
            for (int i = 0; i < 3; i++) {
                Turn turn = session.startTurn("q" + i);
                turn.finish(TurnState.COMPLETED, "a" + i, null);
                session.commitTurn(turn, null);
            }
            store.checkpoint(session, null);
            Session resumed = store.load(session.id()).orElseThrow();
            assertEquals(3, resumed.nextTurnIndex());
            assertEquals(3, resumed.turns().size());
        }
    }

    @Test
    void eventsAreCommittedWithTheTurn() {
        try (LocalSessionStore store = new LocalSessionStore(url())) {
            Session session = session(store);
            Turn turn = session.startTurn("hi");
            session.append(Message.user("hi"));
            turn.finish(TurnState.COMPLETED, "hello", null);
            session.commitTurn(turn, null);

            List<EventRecord> events = store.events(session.id(), 0);
            assertFalse(events.isEmpty());
            assertTrue(events.stream().anyMatch(e -> e.type().equals("turn.committed")));
            assertTrue(events.stream().anyMatch(e -> e.type().equals("message.appended")));
        }
    }

    @Test
    void legacySqliteNameStillWorks() {
        try (SqliteSessionStore store = new SqliteSessionStore(url())) {
            Session session = session(store);
            assertTrue(store.load(session.id()).isPresent());
        }
    }

    private static void step(Turn turn, int index, String finish, int prompt, int completion) {
        turn.addStep(Step.started(index).finished(finish, prompt, completion, 1));
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
