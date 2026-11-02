package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentState;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A turn that did not cross the durability boundary must never be reported as completed. */
class DurabilityFailureTest {

    @TempDir
    Path base;

    @Test
    void commitFailureFailsTheTurnAndStopsItsWriter() throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [execution]
                mode = "local"
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            ScriptedProvider provider = new ScriptedProvider();
            runtime.providers().register(provider);
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("void").build());

            // Simulate a store outage after the agent has been admitted but before its first commit.
            runtime.sessions().close();
            TurnResult result = agent.submit("must be durable").get(10, TimeUnit.SECONDS);

            assertNotNull(provider.lastRequest(), "the model call should have happened before the commit");
            assertEquals(TurnState.FAILED, result.state());
            assertTrue(result.error().contains("could not commit turn"), result.error());
            assertEquals(AgentState.FAILED, agent.state(),
                    "a writer with undurable in-memory state must not accept another turn");
        }
    }
}
