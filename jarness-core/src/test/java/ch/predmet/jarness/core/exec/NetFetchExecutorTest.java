package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.tool.ToolMode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The executor's net.fetch enforces the NET scope and strips HTML. */
class NetFetchExecutorTest {

    @TempDir
    Path workspace;

    @Test
    void fetchEnforcesNetCapabilityAndReturnsText() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path binary = repo.resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        String previous = System.getProperty("jarness.executor");
        System.setProperty("jarness.executor", binary.toString());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = "<html><body><h1>Hello</h1><script>bad()</script><p>world</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (LocalExecutionProvider provider = LocalExecutionProvider.start(repo)) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";
            ExecRequest denied = new ExecRequest("n1", "net.fetch", Map.of("url", url), null,
                    ToolMode.SYNC, "n1", workspace.toString(), "workspace-write",
                    CapabilitySet.of(Capability.of(CapKind.FS_READ)), "default");
            ExecResult rejected = provider.invoke(denied);
            assertFalse(rejected.ok());
            assertTrue(rejected.error().contains("capability denied"), rejected.error());

            ExecRequest allowed = new ExecRequest("n2", "net.fetch", Map.of("url", url), null,
                    ToolMode.SYNC, "n2", workspace.toString(), "workspace-write",
                    CapabilitySet.of(Capability.of(CapKind.NET, "127.0.0.1")), "default");
            ExecResult result = provider.invoke(allowed);
            assertTrue(result.ok(), result.error());
            assertTrue(result.text().contains("Hello"), result.text());
            assertTrue(result.text().contains("world"), result.text());
            assertFalse(result.text().contains("<h1>"), result.text());
        } finally {
            server.stop(0);
            if (previous == null) {
                System.clearProperty("jarness.executor");
            } else {
                System.setProperty("jarness.executor", previous);
            }
        }
    }
}
