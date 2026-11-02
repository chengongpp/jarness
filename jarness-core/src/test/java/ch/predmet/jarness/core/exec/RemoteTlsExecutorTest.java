package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.exec.ExecProviderKind;
import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.tool.ToolMode;
import ch.predmet.jarness.core.Jarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Remote executor over TLS: PKCS12 server keystore, CA-verified Rust client. */
class RemoteTlsExecutorTest {

    @TempDir
    Path base;

    @Test
    void remoteExecutorConnectsOverTls() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path binary = repo.resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Path keystore = base.resolve("server.p12");
        Path cert = base.resolve("server.pem");
        generateCertificate(keystore, cert);
        String token = "tls-enrollment-token";
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"
                execution_providers = ["remote"]

                [execution]
                mode = "local"
                remote_enabled = true
                remote_tls = true
                remote_host = "127.0.0.1"
                remote_port = %d
                remote_token = "%s"
                remote_keystore = "%s"
                remote_keystore_password = "changeit"
                """.formatted(port, token, keystore));

        Process executor = null;
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            ProcessBuilder builder = new ProcessBuilder(binary.toString(),
                    "--connect", "127.0.0.1:" + port,
                    "--tls", "--ca", cert.toString());
            builder.environment().put("JARNESS_EXECUTOR_TOKEN", token);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            executor = builder.start();

            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline
                    && runtime.execution().providers().stream()
                    .noneMatch(p -> p.kind() == ExecProviderKind.REMOTE && p.alive())) {
                Thread.sleep(25);
            }
            assertTrue(runtime.execution().providers().stream()
                            .anyMatch(p -> p.kind() == ExecProviderKind.REMOTE && p.alive()),
                    "TLS remote executor did not register: " + runtime.execution().providers());

            CapabilitySet caps = CapabilitySet.of(
                    Capability.of(CapKind.FS_READ), Capability.of(CapKind.FS_WRITE));
            ExecResult result = runtime.execution().invoke(new ExecRequest("tls-1", "fs.write",
                    Map.of("path", "tls.txt", "content", "over tls"),
                    null, ToolMode.SYNC, "tls-1",
                    base.resolve("tls-workspace").toString(), "workspace-write", caps, "default"));
            assertTrue(result.ok(), result.error());
            assertEquals("over tls", Files.readString(base.resolve("tls-workspace/tls.txt")));
        } finally {
            if (executor != null) {
                executor.destroy();
                executor.waitFor(3, TimeUnit.SECONDS);
                executor.destroyForcibly();
            }
        }
    }

    private static void generateCertificate(Path keystore, Path cert) throws Exception {
        Process keytool = new ProcessBuilder("keytool", "-genkeypair",
                "-alias", "jarness",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost",
                "-ext", "SAN=IP:127.0.0.1,DNS:localhost",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertEquals(0, keytool.waitFor(), "keytool genkeypair failed");
        Process export = new ProcessBuilder("keytool", "-exportcert", "-rfc",
                "-alias", "jarness", "-keystore", keystore.toString(),
                "-storepass", "changeit", "-file", cert.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertEquals(0, export.waitFor(), "keytool exportcert failed");
    }
}
