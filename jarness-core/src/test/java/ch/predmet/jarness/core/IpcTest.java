package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.ipc.Blackboard;
import ch.predmet.jarness.api.ipc.Cell;
import ch.predmet.jarness.api.ipc.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpcTest {

    @TempDir
    Path base;

    private JarnessRuntime runtime() throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [execution]
                mode = "local"
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }

    private static Agent agent(JarnessRuntime runtime, String name, CapabilitySet caps) {
        return runtime.spawn(AgentRequest.builder("void").name(name).capabilities(caps).build());
    }

    @Test
    void blackboardSupportsCasWatchAndLocks() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent owner = agent(runtime, "owner", CapabilitySet.of(Capability.of(CapKind.IPC)));
            Blackboard board = runtime.ipc().createBoard(owner, "task-board");
            AtomicReference<Cell> seen = new AtomicReference<>();
            AutoCloseable watch = board.watch("task-1", seen::set);

            Cell cell = board.write("task-1", "open");
            assertEquals(1, cell.version());
            assertEquals("open", cell.value());
            assertEquals(1, seen.get().version());

            assertTrue(board.compareAndSet("task-1", 1, "claimed").isPresent());
            assertTrue(board.compareAndSet("task-1", 1, "stale").isEmpty());
            assertEquals("claimed", board.read("task-1").orElseThrow().value());
            assertEquals(2, board.read("task-1").orElseThrow().version());

            AutoCloseable lock = board.lock("task-1");
            lock.close();
            assertTrue(board.delete("task-1"));
            assertTrue(board.read("task-1").isEmpty());
            assertNull(seen.get().value(), "delete watcher notification carries a null value");
            assertTrue(board.cells().isEmpty());
            watch.close();
        }
    }

    @Test
    void channelsAreBoundedAndSelectable() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent owner = agent(runtime, "owner", CapabilitySet.of(Capability.of(CapKind.IPC)));
            Channel a = runtime.ipc().createChannel(owner, 1);
            Channel b = runtime.ipc().createChannel(owner, 0);
            assertTrue(a.trySend("first"));
            assertFalse(a.trySend("full"));
            assertEquals(Optional.of("first"), a.tryRecv());

            Thread sender = Thread.ofVirtual().start(() -> b.send("handoff"));
            Optional<Channel.Selection> selected = Channel.select(List.of(a, b), Duration.ofSeconds(2));
            assertTrue(selected.isPresent());
            assertEquals("handoff", selected.get().value());
            sender.join();

            a.close();
            assertThrows(ch.predmet.jarness.api.ipc.ChannelClosedException.class, a::recv);
            b.close();
        }
    }

    @Test
    void handlesRequireIpcCapabilityAndDieWithTheOwner() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent noIpc = agent(runtime, "no-ipc", CapabilitySet.of(Capability.of(CapKind.FS_READ)));
            assertThrows(SecurityException.class, () -> runtime.ipc().createBoard(noIpc, "nope"));

            Agent owner = agent(runtime, "owner", CapabilitySet.of(Capability.of(CapKind.IPC)));
            Blackboard board = runtime.ipc().createBoard(owner, "ephemeral");
            assertEquals(List.of("ephemeral"), runtime.ipc().boards());

            owner.stop("test over");
            assertTrue(board.closed());
            assertTrue(runtime.ipc().boards().isEmpty());
        }
    }

    @Test
    void superAgentCanCreateNamespaces() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent superAgent = runtime.supervisor().ensureSuperAgent();
            assertTrue(superAgent.capabilities().containsKind(CapKind.PLATFORM));
            var namespace = runtime.namespaces().create(new ch.predmet.jarness.api.namespace.NamespaceSpec(
                    "review", ch.predmet.jarness.api.namespace.NamespaceMetadata.of("review",
                    runtime.namespaces().defaultNs().metadata().defaults()),
                    runtime.sessions(), "void", ""));
            assertEquals("review", namespace.id());
        }
    }
}
