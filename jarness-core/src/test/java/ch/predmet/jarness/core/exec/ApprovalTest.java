package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.approval.ApprovalDecision;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.core.Jarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Approval policy behavior in the relay path. */
class ApprovalTest {

    @TempDir
    Path base;

    @Test
    void askWithAllowHandlerRunsTheTool() throws Exception {
        assumeExecutor();
        try (JarnessRuntime runtime = runtime("""
                [approval]
                policy = "ask"
                """)) {
            runtime.approvals().register("test", (request, timeout) -> ApprovalDecision.ALLOW);
            Path workspace = base.resolve("ws-allow");
            TurnResult result = run(runtime, workspace);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertEquals("from the executor", Files.readString(workspace.resolve("relay.txt")));
        }
    }

    @Test
    void askWithDenyHandlerBlocksTheTool() throws Exception {
        assumeExecutor();
        try (JarnessRuntime runtime = runtime("""
                [approval]
                policy = "ask"
                """)) {
            runtime.approvals().register("test", (request, timeout) -> ApprovalDecision.DENY);
            Path workspace = base.resolve("ws-deny");
            Agent agent = spawn(runtime, workspace);
            TurnResult result = agent.submit("write the file").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertFalse(Files.exists(workspace.resolve("relay.txt")));
            assertTrue(agent.session().messages().stream()
                    .flatMap(m -> m.toolResults().stream())
                    .anyMatch(r -> r.text().contains("approval denied")), "expected an approval denial");
        }
    }

    @Test
    void neverRunsOnlyAllowlistedTools() throws Exception {
        assumeExecutor();
        Path workspace = base.resolve("ws-never");
        try (JarnessRuntime runtime = runtime("""
                [approval]
                policy = "never"
                allow = ["write"]
                """)) {
            TurnResult result = run(runtime, workspace);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertEquals("from the executor", Files.readString(workspace.resolve("relay.txt")));
        }

        Path blocked = base.resolve("ws-blocked");
        try (JarnessRuntime runtime = runtime("""
                [approval]
                policy = "never"
                allow = ["read"]
                """)) {
            Agent agent = spawn(runtime, blocked);
            TurnResult result = agent.submit("write the file").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertFalse(Files.exists(blocked.resolve("relay.txt")));
            assertTrue(agent.session().messages().stream()
                    .flatMap(m -> m.toolResults().stream())
                    .anyMatch(r -> r.text().contains("blocked by approval policy")));
        }
    }

    private JarnessRuntime runtime(String extra) throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "standard"

                [execution]
                mode = "relay"

                """ + extra);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        runtime.providers().register(new ScriptedProvider());
        runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
        return runtime;
    }

    private Agent spawn(JarnessRuntime runtime, Path workspace) {
        return runtime.spawn(AgentRequest.builder("standard").name("approval").workspace(workspace).build());
    }

    private TurnResult run(JarnessRuntime runtime, Path workspace) throws Exception {
        return spawn(runtime, workspace).submit("write the file").get(30, TimeUnit.SECONDS);
    }

    private static void assumeExecutor() {
        Path binary = Path.of("..").toAbsolutePath().normalize().resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        System.setProperty("jarness.executor", binary.toString());
    }
}
