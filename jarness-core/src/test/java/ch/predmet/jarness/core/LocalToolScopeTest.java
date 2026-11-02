package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.core.tool.ToolContextImpl;
import ch.predmet.jarness.core.tool.builtin.GlobTool;
import ch.predmet.jarness.core.tool.builtin.GrepTool;
import ch.predmet.jarness.core.tool.builtin.ReadFileTool;
import ch.predmet.jarness.core.tool.builtin.WebFetchTool;
import ch.predmet.jarness.core.tool.builtin.WebSearchTool;
import ch.predmet.jarness.core.tool.builtin.WriteFileTool;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit local execution must enforce resource scopes too. */
class LocalToolScopeTest {

    @TempDir
    Path base;

    @Test
    void localFilesHonorScopeAndRealPaths() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            Path cwd = runtime.namespaces().defaultNs().cwd();
            Path scope = Files.createDirectories(cwd.resolve("scope"));
            Path sibling = Files.writeString(cwd.resolve("scope-secret.txt"), "sibling");
            Path outside = Files.writeString(cwd.resolve("outside.txt"), "outside");
            Files.createSymbolicLink(scope.resolve("link.txt"), outside);
            CapabilitySet caps = CapabilitySet.of(
                    new Capability("read", CapKind.FS_READ, scope + "/*", null),
                    new Capability("write", CapKind.FS_WRITE, scope + "/*", null));
            Agent agent = runtime.spawn(AgentRequest.builder("void").capabilities(caps).build());
            ToolContext ctx = new ToolContextImpl(agent, runtime, () -> false, () -> "");

            assertThrows(ToolException.class, () -> new ReadFileTool().invoke(
                    invocation("read", "file_path", sibling.toString()), ctx));
            assertThrows(ToolException.class, () -> new ReadFileTool().invoke(
                    invocation("read", "file_path", scope.resolve("link.txt").toString()), ctx));
            assertThrows(ToolException.class, () -> new WriteFileTool().invoke(
                    new ToolInvocation("w", "write", Map.of("file_path", scope.resolve("link.txt").toString(),
                            "content", "bad"), "{}"), ctx));
            assertEquals("outside", Files.readString(outside));

            assertTrue(new WriteFileTool().invoke(new ToolInvocation("new", "write",
                    Map.of("file_path", scope.resolve("nested/new.txt").toString(), "content", "safe"),
                    "{}"), ctx).ok());
            assertTrue(new GlobTool().invoke(new ToolInvocation("g", "glob",
                    Map.of("path", scope.toString(), "pattern", "*"), "{}"), ctx).ok());
            assertFalse(new GrepTool().invoke(new ToolInvocation("s", "grep",
                    Map.of("path", scope.toString(), "pattern", "outside"), "{}"), ctx)
                    .text().contains("outside"));
        }
    }

    @Test
    void localFetchChecksRedirectHostAndSearchChecksBackendScope() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://localhost:" + server.getAddress().getPort() + "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try (JarnessRuntime runtime = runtime()) {
            Agent fetcher = runtime.spawn(AgentRequest.builder("void")
                    .capabilities(CapabilitySet.of(Capability.of(CapKind.NET, "127.0.0.1"))).build());
            ToolContext fetchContext = new ToolContextImpl(fetcher, runtime, () -> false, () -> "");
            assertThrows(ToolException.class, () -> new WebFetchTool().invoke(
                    invocation("web_fetch", "url", "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/redirect"), fetchContext));

            Agent searcher = runtime.spawn(AgentRequest.builder("void")
                    .capabilities(CapabilitySet.of(Capability.of(CapKind.NET, "example.com"))).build());
            ToolContext searchContext = new ToolContextImpl(searcher, runtime, () -> false, () -> "");
            ToolException denied = assertThrows(ToolException.class, () -> new WebSearchTool().invoke(
                    new ToolInvocation("search", "web_search", Map.of("queries", java.util.List.of("test")),
                            "{}"), searchContext));
            assertTrue(denied.getMessage().contains("lite.duckduckgo.com"), denied.getMessage());
        } finally {
            server.stop(0);
        }
    }

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

    private static ToolInvocation invocation(String tool, String key, String value) {
        return new ToolInvocation(tool, tool, Map.of(key, value), "{}");
    }
}
