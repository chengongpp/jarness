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
            Agent agent = spawn(runtime, "approval-allow");
            TurnResult result = run(agent);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertEquals("from the executor", Files.readString(agent.workspace().resolve("relay.txt")));
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
            Agent agent = spawn(runtime, "approval-deny");
            TurnResult result = agent.submit("write the file").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertFalse(Files.exists(agent.workspace().resolve("relay.txt")));
            assertTrue(agent.session().messages().stream()
                    .flatMap(m -> m.toolResults().stream())
                    .anyMatch(r -> r.text().contains("approval denied")), "expected an approval denial");
        }
    }

    @Test
    void neverRunsOnlyAllowlistedTools() throws Exception {
        assumeExecutor();
        try (JarnessRuntime runtime = runtime("""
                [approval]
                policy = "never"
                allow = ["write"]
                """, "ws-never")) {
            Agent agent = spawn(runtime, "approval-never");
            TurnResult result = run(agent);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertEquals("from the executor", Files.readString(agent.workspace().resolve("relay.txt")));
        }

        try (JarnessRuntime runtime = runtime("""
                [approval]
                policy = "never"
                allow = ["read"]
                """, "ws-blocked")) {
            Agent agent = spawn(runtime, "approval-blocked");
            TurnResult result = agent.submit("write the file").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertFalse(Files.exists(agent.workspace().resolve("relay.txt")));
            assertTrue(agent.session().messages().stream()
                    .flatMap(m -> m.toolResults().stream())
                    .anyMatch(r -> r.text().contains("blocked by approval policy")));
        }
    }

    @Test
    void neverAlsoBlocksMutatingIpcTools() throws Exception {
        try (JarnessRuntime runtime = runtime("""
                [approval]
                policy = "never"
                allow = []
                """)) {
            runtime.providers().register(new ScriptedProvider("shm",
                    "{\"op\":\"write\",\"name\":\"tasks\",\"cell\":\"one\",\"value\":\"open\"}"));
            Agent agent = spawn(runtime, "approval-ipc");
            TurnResult result = agent.submit("write shared state").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertTrue(agent.session().messages().stream().flatMap(m -> m.toolResults().stream())
                    .anyMatch(r -> r.text().contains("blocked by approval policy")));
            assertTrue(runtime.ipc().shms("default").isEmpty(), "the mutating shm call must not execute");
        }
    }

    private JarnessRuntime runtime(String extra) throws Exception {
        return runtime(extra, "ws");
    }

    private JarnessRuntime runtime(String extra, String cwd) throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "standard"
                cwd = "%s"

                [execution]
                mode = "relay"

                """.formatted(cwd) + extra);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        runtime.providers().register(new ScriptedProvider());
        runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
        return runtime;
    }

    private Agent spawn(JarnessRuntime runtime, String name) {
        return runtime.spawn(AgentRequest.builder("standard").name(name).build());
    }

    private TurnResult run(Agent agent) throws Exception {
        return agent.submit("write the file").get(30, TimeUnit.SECONDS);
    }

    private static void assumeExecutor() {
        Path binary = Path.of("..").toAbsolutePath().normalize().resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        System.setProperty("jarness.executor", binary.toString());
    }
}
