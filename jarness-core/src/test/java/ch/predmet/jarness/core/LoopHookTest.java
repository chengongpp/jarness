package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.loop.LoopHook;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.core.exec.ScriptedProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopHookTest {

    @TempDir
    Path base;

    @Test
    void loopHooksSeeTurnAndStepBoundaries() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            runtime.providers().register(new ScriptedProvider());
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            Counters counters = new Counters();
            runtime.loopHooks().register(counters);
            Agent agent = runtime.spawn(AgentRequest.builder("void").name("hooks").build());
            agent.submit("hi").get(10, TimeUnit.SECONDS);
            assertEquals(1, counters.beforeTurn.get());
            assertEquals(1, counters.afterTurn.get());
            assertEquals(2, counters.beforeStep.get());
            assertEquals(2, counters.afterStep.get());
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

    private static final class Counters implements LoopHook {
        final AtomicInteger beforeTurn = new AtomicInteger();
        final AtomicInteger beforeStep = new AtomicInteger();
        final AtomicInteger afterStep = new AtomicInteger();
        final AtomicInteger afterTurn = new AtomicInteger();

        @Override
        public String name() {
            return "counters";
        }

        @Override
        public void beforeTurn(Agent agent, Turn turn) {
            beforeTurn.incrementAndGet();
        }

        @Override
        public void beforeStep(Agent agent, Turn turn, int step) {
            beforeStep.incrementAndGet();
        }

        @Override
        public void afterStep(Agent agent, Turn turn, int step) {
            afterStep.incrementAndGet();
        }

        @Override
        public void afterTurn(Agent agent, Turn turn) {
            afterTurn.incrementAndGet();
        }
    }
}
