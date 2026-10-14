package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Role;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.SessionInit;
import ch.predmet.jarness.api.session.Step;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip tests for the SQLite session store. */
class SqliteSessionStoreTest {

    @TempDir
    Path dir;

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("sessions.db");
    }

    @Test
    void createAppendResumeDelete() {
        String sessionId;
        try (SqliteSessionStore store = new SqliteSessionStore(url())) {
            Session session = store.create(new SessionInit(null, "agent-1", null, dir, "standard", null,
                    Map.of("origin", "test")));
            sessionId = session.id();
            session.append(Message.user("hello"));
            session.append(new Message(null, Role.ASSISTANT, List.of(
                    new ContentBlock.Reasoning("thinking"),
                    new ContentBlock.Text("hi"),
                    new ContentBlock.ToolCall("c1", "read", "{\"file_path\":\"a.txt\"}")), null));
            session.append(Message.toolResult("c1", "read", true, "content"));
            Turn turn = session.startTurn("hello");
            turn.addStep(Step.started(0).finished("tool_calls", 10, 5, 1));
            turn.finish(TurnState.COMPLETED, "hi", null);
            session.saveTurn(turn);
        }
        try (SqliteSessionStore store = new SqliteSessionStore(url())) {
            assertTrue(store.load(sessionId).isPresent());
            Session resumed = store.load(sessionId).orElseThrow();
            assertEquals("agent-1", resumed.agentId());
            assertEquals(3, resumed.messages().size());
            assertEquals(1, resumed.turns().size());
            assertEquals(TurnState.COMPLETED, resumed.turns().get(0).state());
            assertEquals(1, resumed.turns().get(0).steps().size());
            assertEquals(10, resumed.turns().get(0).promptTokens());
            // blocks survive the round trip, reasoning included
            Message assistant = resumed.messages().get(1);
            assertEquals("thinking", assistant.reasoning());
            assertEquals("hi", assistant.text());
            assertEquals(1, assistant.toolCalls().size());
            assertEquals("c1", assistant.toolCalls().get(0).callId());
            assertEquals(1, resumed.nextTurnIndex());
            assertEquals("test", resumed.metadata().get("origin"));

            assertEquals(1, store.list(10).size());
            assertEquals(1, store.listByAgent("agent-1").size());
            assertEquals(0, store.listByAgent("nobody").size());

            assertTrue(store.delete(sessionId));
            assertFalse(store.load(sessionId).isPresent());
        }
    }

    @Test
    void messageCodecRoundTrip() {
        List<ContentBlock> blocks = List.of(
                new ContentBlock.Text("text"),
                new ContentBlock.Reasoning("why"),
                new ContentBlock.ToolCall("id1", "bash", "{\"command\":\"ls\"}"),
                new ContentBlock.ToolResult("id1", "bash", true, "ok"));
        List<ContentBlock> back = MessageCodec.readBlocks(MessageCodec.writeBlocks(blocks));
        assertEquals(blocks, back);
        assertTrue(MessageCodec.readBlocks("not json").isEmpty());
        assertTrue(MessageCodec.readBlocks(null).isEmpty());
    }
}
