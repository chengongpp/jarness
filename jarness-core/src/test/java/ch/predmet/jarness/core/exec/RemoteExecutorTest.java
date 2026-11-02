package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
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

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Remote executor: callback listener, enrollment token, and a call over the network path. */
class RemoteExecutorTest {

    @TempDir
    Path base;

    @Test
    void remoteExecutorConnectsAndRunsATool() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path binary = repo.resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        String token = "remote-enrollment-token";
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"
                execution_providers = ["remote"]

                [execution]
                mode = "local"
                remote_enabled = true
                remote_host = "127.0.0.1"
                remote_port = %d
                remote_token = "%s"
                """.formatted(port, token));

        Process executor = null;
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            ProcessBuilder builder = new ProcessBuilder(binary.toString(),
                    "--connect", "127.0.0.1:" + port);
            builder.environment().put("JARNESS_EXECUTOR_TOKEN", token);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            executor = builder.start();

            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && runtime.execution().providers().stream()
                    .noneMatch(p -> p.kind() == ExecProviderKind.REMOTE && p.alive())) {
                Thread.sleep(25);
            }
            assertTrue(runtime.execution().providers().stream()
                            .anyMatch(p -> p.kind() == ExecProviderKind.REMOTE && p.alive()),
                    "remote executor did not register: " + runtime.execution().providers());

            CapabilitySet caps = CapabilitySet.of(
                    Capability.of(CapKind.FS_READ), Capability.of(CapKind.FS_WRITE));
            ExecResult result = runtime.execution().invoke(new ExecRequest("remote-1", "fs.write",
                    Map.of("path", "remote.txt", "content", "from a remote executor"),
                    null, ToolMode.SYNC, "remote-1",
                    base.resolve("workspace").toString(), "workspace-write", caps, "default"));
            assertTrue(result.ok(), result.error());
            assertTrue(Files.readString(base.resolve("workspace/remote.txt")).contains("remote executor"));

            ExecutionProvider remote = runtime.execution().providers().stream()
                    .filter(p -> p.kind() == ExecProviderKind.REMOTE).findFirst().orElseThrow();
            executor.destroy();
            executor.waitFor(3, TimeUnit.SECONDS);
            long disconnectedAt = System.currentTimeMillis() + 5_000;
            while (remote.alive() && System.currentTimeMillis() < disconnectedAt) {
                Thread.sleep(25);
            }
            assertFalse(remote.alive(), "EOF must mark a remote provider disconnected");
            ExecutionException noProvider = assertThrows(ExecutionException.class,
                    () -> runtime.execution().invoke(new ExecRequest("after-disconnect", "fs.read",
                            Map.of("path", "remote.txt"), null, ToolMode.SYNC, "after-disconnect",
                            base.resolve("workspace").toString(), "workspace-write", caps, "default")));
            assertTrue(noProvider.getMessage().contains("namespace \"default\""), noProvider.getMessage());
        } finally {
            if (executor != null) {
                executor.destroy();
                executor.waitFor(3, TimeUnit.SECONDS);
                executor.destroyForcibly();
            }
        }
    }
}
