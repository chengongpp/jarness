package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.ipc.Cell;
import ch.predmet.jarness.api.ipc.Channel;
import ch.predmet.jarness.api.ipc.Shm;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceMetadata;
import ch.predmet.jarness.api.namespace.NamespaceSpec;
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
        return runtime(base);
    }

    private JarnessRuntime runtime(Path root) throws Exception {
        JarnessLayout layout = JarnessLayout.of(root);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [execution]
                mode = "local"
                """);
        JarnessRuntime runtime = Jarness.Builder.at(root).build();
        runtime.start();
        return runtime;
    }

    private static Agent agent(JarnessRuntime runtime, String name, CapabilitySet caps) {
        return runtime.spawn(AgentRequest.builder("void").name(name).capabilities(caps).build());
    }

    @Test
    void shmSupportsCasWatchAndLocks() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent owner = agent(runtime, "owner", CapabilitySet.of(Capability.of(CapKind.IPC)));
            Shm shm = runtime.ipc().createShm(owner, "task-board");
            AtomicReference<Cell> seen = new AtomicReference<>();
            AutoCloseable watch = shm.watch("task-1", seen::set);

            Cell cell = shm.write("task-1", "open");
            assertEquals(1, cell.version());
            assertEquals("open", cell.value());
            assertEquals(1, seen.get().version());

            assertTrue(shm.compareAndSet("task-1", 1, "claimed").isPresent());
            assertTrue(shm.compareAndSet("task-1", 1, "stale").isEmpty());
            assertEquals("claimed", shm.read("task-1").orElseThrow().value());
            assertEquals(2, shm.read("task-1").orElseThrow().version());

            AutoCloseable lock = shm.lock("task-1");
            lock.close();
            assertTrue(shm.delete("task-1"));
            assertTrue(shm.read("task-1").isEmpty());
            assertNull(seen.get().value(), "delete watcher notification carries a null value");
            assertTrue(shm.cells().isEmpty());
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
    void shmRequiresIpcAndOutlivesItsCreator() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent noIpc = agent(runtime, "no-ipc", CapabilitySet.of(Capability.of(CapKind.FS_READ)));
            assertThrows(SecurityException.class, () -> runtime.ipc().createShm(noIpc, "nope"));

            Agent owner = agent(runtime, "owner", CapabilitySet.of(Capability.of(CapKind.IPC)));
            Shm shm = runtime.ipc().createShm(owner, "ephemeral");
            shm.write("handoff", "survives");
            assertEquals(List.of("ephemeral"), runtime.ipc().shms("default"));

            owner.stop("test over");
            Shm reopened = runtime.ipc().attachShm(
                    agent(runtime, "other", CapabilitySet.of(Capability.of(CapKind.IPC))), "ephemeral");
            assertEquals("survives", reopened.read("handoff").orElseThrow().value());
        }
    }

    @Test
    void shmCellsPersistAcrossRuntimeRestart() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent owner = agent(runtime, "writer", CapabilitySet.of(Capability.of(CapKind.IPC)));
            runtime.ipc().createShm(owner, "durable").write("status", "committed");
        }
        try (JarnessRuntime runtime = runtime()) {
            Agent reader = agent(runtime, "reader", CapabilitySet.of(Capability.of(CapKind.IPC)));
            Shm shm = runtime.ipc().attachShm(reader, "durable");
            assertEquals("committed", shm.read("status").orElseThrow().value());
            assertEquals(1, shm.read("status").orElseThrow().version());
        }
    }

    @Test
    void shmNamesAreScopedToTheNamespace() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent owner = agent(runtime, "owner", CapabilitySet.of(Capability.of(CapKind.IPC)));
            Shm first = runtime.ipc().createShm(owner, "same-name");
            first.write("value", "default");

            Namespace other = runtime.namespaces().create(new NamespaceSpec(
                    "other", NamespaceMetadata.of("other", base.resolve("other-cwd"),
                    runtime.namespaces().defaultNs().metadata().defaults()),
                    runtime.sessions(), "void", ""));
            Agent otherAgent = runtime.spawn(AgentRequest.builder("void").namespace("other")
                    .capabilities(CapabilitySet.of(Capability.of(CapKind.IPC))).build());
            Shm second = runtime.ipc().attachShm(otherAgent, "same-name");
            assertEquals(other.id(), second.namespaceId());
            assertTrue(second.read("value").isEmpty());
            second.write("value", "other");
            assertEquals("default", first.read("value").orElseThrow().value());
            assertEquals(List.of("same-name"), runtime.ipc().shms("other"));
        }
    }

    @Test
    void superAgentCanCreateNamespaces() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Agent superAgent = runtime.supervisor().ensureSuperAgent();
            assertTrue(superAgent.capabilities().containsKind(CapKind.PLATFORM));
            var namespace = runtime.namespaces().create(new NamespaceSpec(
                    "review", NamespaceMetadata.of("review", base.resolve("review-cwd"),
                    runtime.namespaces().defaultNs().metadata().defaults()),
                    runtime.sessions(), "void", ""));
            assertEquals("review", namespace.id());
        }
    }
}
