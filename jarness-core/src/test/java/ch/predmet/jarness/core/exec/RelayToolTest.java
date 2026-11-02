package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.SimpleAgentTemplate;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.core.Jarness;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolForm;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies that relay mode wires executor-backed tools and that the provider is alive. */
class RelayToolTest {

    @TempDir
    Path base;

    @Test
    void relayModeRegistersExecutorTools() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path binary = repo.resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        String previous = System.getProperty("jarness.executor");
        System.setProperty("jarness.executor", binary.toString());
        try {
            JarnessLayout layout = JarnessLayout.of(base);
            Files.createDirectories(layout.configDir());
            Files.writeString(layout.configFile(), """
                    [defaults]
                    template = "void"

                    [execution]
                    mode = "relay"
                    """);
            try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
                runtime.start();
                Tool read = runtime.tools().require("read");
                assertEquals(ToolForm.RELAY, read.form());
                assertEquals("fs.read", read.relayName());
                assertEquals(ToolForm.RELAY, runtime.tools().require("bash").form());
                assertTrue(runtime.execution().healthy());
            }
        } finally {
            if (previous == null) {
                System.clearProperty("jarness.executor");
            } else {
                System.setProperty("jarness.executor", previous);
            }
        }
    }

    @Test
    void apiOnlyRelayDeclarationIsRoutedWithoutACoreWrapper() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path binary = repo.resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        String previous = System.getProperty("jarness.executor");
        System.setProperty("jarness.executor", binary.toString());
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [execution]
                mode = "relay"
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            runtime.tools().register(new DeclarativeRead());
            runtime.providers().register(new ScriptedProvider("declarative_read",
                    "{\"file_path\":\"declarative.txt\"}"));
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder(SimpleAgentTemplate.builder("api-relay")
                    .name("API relay").tools("declarative_read").build()).build());
            Files.writeString(agent.workspace().resolve("declarative.txt"), "routed by declaration");

            TurnResult result = agent.submit("read it").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertTrue(agent.session().messages().stream().flatMap(m -> m.toolResults().stream())
                    .anyMatch(r -> r.ok() && r.text().contains("routed by declaration")));
        } finally {
            if (previous == null) {
                System.clearProperty("jarness.executor");
            } else {
                System.setProperty("jarness.executor", previous);
            }
        }
    }

    @Test
    void relayBackgroundBashUsesTheJobRegistry() throws Exception {
        Path binary = Path.of("..").toAbsolutePath().normalize()
                .resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        String previous = System.getProperty("jarness.executor");
        System.setProperty("jarness.executor", binary.toString());
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "standard"

                [execution]
                mode = "relay"
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            runtime.providers().register(new ScriptedProvider("bash",
                    "{\"command\":\"printf background-ok\",\"run_in_background\":true}"));
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("standard").build());

            TurnResult result = agent.submit("run in background").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertTrue(runtime.jobs().awaitAll(Duration.ofSeconds(10)));
            assertEquals(1, runtime.jobs().list().size());
            String output = runtime.jobs().read(runtime.jobs().list().get(0).id()).orElse("");
            assertTrue(output.contains("background-ok"), output);
        } finally {
            if (previous == null) {
                System.clearProperty("jarness.executor");
            } else {
                System.setProperty("jarness.executor", previous);
            }
        }
    }

    /** Uses only jarness-api declarations; invoke must never be called for this RELAY tool. */
    private static final class DeclarativeRead implements Tool {
        private final ToolSpec spec = ToolSpec.readOnly("declarative_read", "Read through a declaration.", Map.of());

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
            throw new AssertionError("a RELAY declaration must be routed by the dispatcher");
        }

        @Override
        public ToolForm form() {
            return ToolForm.RELAY;
        }

        @Override
        public String relayName() {
            return "fs.read";
        }
    }
}
