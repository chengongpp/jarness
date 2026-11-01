package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentState;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.core.exec.ScriptedProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Super agent creation, platform capabilities, and a platform tool turn. */
class SuperAgentTest {

    @TempDir
    Path base;

    @Test
    void superAgentHoldsPlatformCapabilitiesAndParentsRoots() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            runtime.providers().register(new ScriptedProvider("daemon_status", "{}"));
            runtime.providers().setDefault(new ch.predmet.jarness.api.provider.ModelRef(
                    ScriptedProvider.ID, ScriptedProvider.MODEL));

            Agent superAgent = runtime.supervisor().ensureSuperAgent();
            assertEquals(0L, superAgent.pid());
            assertEquals(0, superAgent.depth());
            assertEquals(AgentState.IDLE, superAgent.state());
            assertTrue(superAgent.capabilities().containsKind(CapKind.PLATFORM));
            assertTrue(superAgent.ctx().visibleToolNames().contains("daemon_status"));

            Agent root = runtime.spawn(AgentRequest.builder("void").name("root").build());
            assertEquals(0L, root.parentPid());
            assertFalse(root.capabilities().containsKind(CapKind.PLATFORM));
            assertEquals(2, runtime.supervisor().tree(0).size());
            assertEquals(1, runtime.supervisor().tree(0).children().size());

            TurnResult result = superAgent.submit("report daemon status").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertTrue(superAgent.session().messages().stream()
                    .flatMap(m -> m.toolResults().stream())
                    .anyMatch(r -> r.ok() && r.text().contains("jarness")), "daemon_status should have run");
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
}
