package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.SimpleAgentTemplate;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolMode;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;
import ch.predmet.jarness.core.exec.ScriptedProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mailbox delivery and async tool completion open the next turn. */
class MailboxTurnTest {

    @TempDir
    Path base;

    @Test
    void supervisorDeliverBecomesATurn() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            runtime.providers().register(new ScriptedProvider());
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("void").name("mailbox").build());

            assertTrue(runtime.supervisor().deliver(agent.id(), "hello via mailbox"));
            long deadline = System.currentTimeMillis() + 5_000;
            while (agent.turnCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, agent.turnCount());
            assertTrue(agent.session().messages().stream()
                    .anyMatch(m -> m.text().contains("hello via mailbox")));
        }
    }

    @Test
    void asyncToolCompletionOpensANewTurn() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            runtime.providers().register(new ScriptedProvider("slow", "{}"));
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            runtime.tools().register(new SlowTool());
            SimpleAgentTemplate template = SimpleAgentTemplate.builder("async")
                    .name("Async")
                    .systemPrompt("Call slow when asked.")
                    .tools("slow")
                    .build();
            Agent agent = runtime.spawn(AgentRequest.builder(template).name("async").build());

            TurnResult first = agent.submit("do the slow thing").get(10, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, first.state(), first.error());

            long deadline = System.currentTimeMillis() + 5_000;
            while (agent.turnCount() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(agent.turnCount() >= 2, "async result should open a new turn");
            assertTrue(agent.session().messages().stream()
                    .anyMatch(m -> m.text().contains("Async tool result") && m.text().contains("slow done")));
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

                [mailbox]
                capacity = 16
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }

    /** An async tool that finishes shortly after acceptance. */
    private static final class SlowTool implements Tool {

        private final ToolSpec spec = ToolSpec.of("slow", "Finish slowly.");

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
            Thread.sleep(100);
            return ToolOutcome.ok("slow done");
        }

        @Override
        public ToolMode mode() {
            return ToolMode.ASYNC;
        }
    }
}
