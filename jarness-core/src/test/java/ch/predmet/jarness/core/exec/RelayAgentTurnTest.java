package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.core.Jarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end: a scripted model asks for {@code write}, the daemon relays it to the Rust executor,
 * and the file appears in the namespace cwd.
 */
class RelayAgentTurnTest {

    @TempDir
    Path base;

    @Test
    void modelToolCallRunsInTheExecutor() throws Exception {
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
                    template = "standard"

                    [execution]
                    mode = "relay"
                    """);
            try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
                runtime.start();
                runtime.providers().register(new ScriptedProvider());
                runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));

                Agent agent = runtime.spawn(AgentRequest.builder("standard")
                        .name("relay-agent")
                        .build());
                TurnResult result = agent.submit("write the file").get(30, TimeUnit.SECONDS);
                assertEquals(TurnState.COMPLETED, result.state(), result.error());
                assertEquals("done", result.output());
                assertEquals("from the executor", Files.readString(agent.workspace().resolve("relay.txt")));
                assertTrue(agent.session().messages().stream()
                        .anyMatch(m -> !m.toolResults().isEmpty()), "expected a tool observation");
            }
        } finally {
            if (previous == null) {
                System.clearProperty("jarness.executor");
            } else {
                System.setProperty("jarness.executor", previous);
            }
        }
    }

}
