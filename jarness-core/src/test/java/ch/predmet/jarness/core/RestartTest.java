package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentState;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.kernel.AgentManifest;
import ch.predmet.jarness.api.kernel.RestartPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart policy: a failed agent is recreated from its image. */
class RestartTest {

    @TempDir
    Path base;

    @Test
    void failedAgentIsRestartedFromItsSession() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            long pid = runtime.supervisor().spawn(0, "default",
                    AgentManifest.builder("void").name("restartable").restart(RestartPolicy.ON_FAILURE).build(),
                    null, null);
            Agent original = runtime.supervisor().agent(pid);
            String sessionId = original.session().id();

            forceFailed(original);
            original.stop("boom");

            long deadline = System.currentTimeMillis() + 5_000;
            Agent restarted = null;
            while (System.currentTimeMillis() < deadline) {
                restarted = runtime.supervisor().agents().stream()
                        .filter(a -> a.pid() != pid && a.session().id().equals(sessionId))
                        .findFirst().orElse(null);
                if (restarted != null) {
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(restarted != null, "expected a restarted agent");
            assertNotEquals(pid, restarted.pid());
            assertEquals(sessionId, restarted.session().id());
            assertEquals(RestartPolicy.ON_FAILURE, restarted.manifest().restart());
        }
    }

    @SuppressWarnings("unchecked")
    private static void forceFailed(Agent agent) throws Exception {
        Field field = agent.getClass().getDeclaredField("state");
        field.setAccessible(true);
        ((AtomicReference<AgentState>) field.get(agent)).set(AgentState.FAILED);
    }

    private JarnessRuntime runtime() throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [execution]
                mode = "local"

                [supervisor]
                restart_delay_ms = 50
                max_restarts = 3
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }
}
