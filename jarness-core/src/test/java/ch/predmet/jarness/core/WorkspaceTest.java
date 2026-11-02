package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelRef;
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

/** All agents in a namespace, including subagents, share the namespace metadata cwd. */
class WorkspaceTest {

    @TempDir
    Path base;

    @Test
    void subagentSharesTheNamespaceCwdAndGetsNoPrivateWorkspace() throws Exception {
        Path cwd = base.resolve("shared-ws").toAbsolutePath().normalize();
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "standard"
                cwd = "shared-ws"

                [execution]
                mode = "local"
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            runtime.providers().register(new ScriptedProvider("subagent",
                    "{\"description\":\"inspect cwd\",\"prompt\":\"report your cwd\"}"));
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));

            Agent parent = runtime.spawn(AgentRequest.builder("standard").name("parent").build());
            assertEquals(cwd, parent.workspace());
            TurnResult result = parent.submit("delegate the cwd check").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());

            var child = runtime.supervisor().statuses().stream()
                    .filter(status -> status.name().startsWith("sub:"))
                    .findFirst();
            assertTrue(child.isPresent(), "expected a subagent in the supervision tree");
            assertEquals(cwd, child.get().workspace());
            assertFalse(Files.exists(cwd.resolve("subagents")),
                    "subagents must not create their own workspace directories");
        }
    }
}
