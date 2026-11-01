package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.EventRecord;
import ch.predmet.jarness.core.exec.ScriptedProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationTest {

    @TempDir
    Path base;

    @Test
    void committedTurnsReachSubscribers() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            runtime.providers().register(new ScriptedProvider());
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("void").name("observed").build());

            CountDownLatch latch = new CountDownLatch(1);
            List<EventRecord> received = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = runtime.observation().subscribe(agent.session().id(), event -> {
                received.add(event);
                if (event.type().equals("turn.committed")) {
                    latch.countDown();
                }
            })) {
                agent.submit("hi").get(10, TimeUnit.SECONDS);
                assertTrue(latch.await(5, TimeUnit.SECONDS), "expected a live turn.committed event");
            }
            assertFalse(received.isEmpty());
            assertTrue(runtime.observation().events(agent.session().id(), 0).stream()
                    .anyMatch(e -> e.type().equals("turn.committed")));
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

                [observe]
                poll_ms = 50
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }
}
