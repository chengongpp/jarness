package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.exec.ExecProviderKind;
import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.exec.ExecutionException;
import ch.predmet.jarness.api.exec.ExecutionProvider;
import ch.predmet.jarness.api.tool.ToolMode;
import ch.predmet.jarness.core.Jarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Namespace-scoped execution routing without requiring a real executor process. */
class ExecutionRoutingTest {

    @TempDir
    Path base;

    @Test
    void namespacesSelectProviderIdsOrKindsInDeclaredOrder() throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"
                execution_providers = ["local"]

                [namespaces.remote]
                template = "void"
                execution_providers = ["remote"]

                [execution]
                mode = "local"
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            FakeProvider local = new FakeProvider("local-executor", ExecProviderKind.LOCAL);
            FakeProvider remote = new FakeProvider("remote-executor-1", ExecProviderKind.REMOTE);
            runtime.execution().register(local);
            runtime.execution().register(remote);

            assertEquals(List.of("local"), runtime.namespaces().defaultNs().executionProviders());
            assertEquals(List.of("remote"), runtime.namespaces().get("remote").executionProviders());
            assertEquals("local-executor", runtime.execution().invoke(request("default")).text());
            assertEquals("remote-executor-1", runtime.execution().invoke(request("remote")).text());
            assertEquals(1, local.calls);
            assertEquals(1, remote.calls);

            remote.alive = false;
            ExecutionException denied = assertThrows(ExecutionException.class,
                    () -> runtime.execution().invoke(request("remote")));
            assertEquals(ExecutionException.Reason.NO_PROVIDER, denied.reason());
            assertTrue(denied.getMessage().contains("route remote"), denied.getMessage());
            assertEquals(1, remote.calls, "a disconnected provider must not receive more calls");
        }
    }

    private static ExecRequest request(String namespace) {
        return new ExecRequest(namespace + "-call", "fs.read", Map.of("path", "a.txt"), null,
                ToolMode.SYNC, namespace + "-call", "/tmp", "workspace-write",
                CapabilitySet.empty(), namespace);
    }

    private static final class FakeProvider implements ExecutionProvider {
        private final String id;
        private final ExecProviderKind kind;
        private volatile boolean alive = true;
        private int calls;

        private FakeProvider(String id, ExecProviderKind kind) {
            this.id = id;
            this.kind = kind;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ExecProviderKind kind() {
            return kind;
        }

        @Override
        public Set<String> tools() {
            return Set.of("fs.read");
        }

        @Override
        public Set<CapKind> capabilities() {
            return Set.of(CapKind.FS_READ);
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public ExecResult invoke(ExecRequest request) {
            calls++;
            return ExecResult.ok(id);
        }

        @Override
        public void cancel(String callId, String reason) {
        }
    }
}
