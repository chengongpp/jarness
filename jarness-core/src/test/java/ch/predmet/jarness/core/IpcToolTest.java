package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.ipc.Shm;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.core.exec.ScriptedProvider;
import ch.predmet.jarness.core.tool.ToolContextImpl;
import ch.predmet.jarness.core.tool.builtin.platform.AgentSendTool;
import ch.predmet.jarness.core.tool.builtin.platform.ShmTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IPC tools must stay inside the daemon JVM and must not depend on an executor. */
class IpcToolTest {

    @TempDir
    Path base;

    @Test
    void shmToolRunsInJvmEvenWhenNoExecutorIsAlive() throws Exception {
        String previous = System.getProperty("jarness.executor");
        System.setProperty("jarness.executor", base.resolve("missing-executor").toString());
        try (JarnessRuntime runtime = runtime("""
                [defaults]
                template = "standard"
                cwd = "shared-ws"

                [execution]
                mode = "relay"
                """, new ScriptedProvider("shm",
                "{\"op\":\"write\",\"name\":\"board\",\"cell\":\"key\",\"value\":\"from-jvm\"}"))) {
            assertTrue(runtime.execution().providers().isEmpty(), "no executor should be registered");
            Agent agent = runtime.spawn(AgentRequest.builder("standard").name("ipc-agent").build());
            TurnResult result = agent.submit("write shared memory").get(30, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertTrue(agent.session().messages().stream()
                    .flatMap(message -> message.toolResults().stream())
                    .anyMatch(toolResult -> toolResult.text().contains("wrote board/key v1")),
                    "expected the shm observation in the transcript");

            Shm shm = runtime.ipc().findShm("default", "board").orElseThrow();
            assertEquals("from-jvm", shm.read("key").orElseThrow().value());
        } finally {
            restoreExecutor(previous);
        }
    }

    @Test
    void agentSendDeliversToTheMailboxInJvm() throws Exception {
        try (JarnessRuntime runtime = runtime("""
                [defaults]
                template = "void"
                cwd = "shared-ws"

                [execution]
                mode = "local"
                """, new ScriptedProvider())) {
            CapabilitySet caps = CapabilitySet.of(Capability.of(CapKind.IPC));
            Agent target = runtime.spawn(AgentRequest.builder("void").name("target").capabilities(caps).build());
            Agent sender = runtime.spawn(AgentRequest.builder("void").name("sender").capabilities(caps).build());

            ToolOutcome result = new AgentSendTool().invoke(
                    new ToolInvocation("c1", "agent_send",
                            Map.of("pid", target.pid(), "message", "ping from jvm"), "{}"),
                    new ToolContextImpl(sender, runtime, () -> false, () -> ""));
            assertTrue(result.ok(), result.error());

            boolean delivered = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (target.session().messages().stream().anyMatch(message -> hasText(message, "ping from jvm"))) {
                    delivered = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(delivered, "the target agent should have opened a turn for the mailbox message");
        }
    }

    @Test
    void toolsAreDeclaredLocal() {
        assertEquals(ch.predmet.jarness.api.tool.ToolForm.LOCAL, new ShmTool().form());
        assertEquals(ch.predmet.jarness.api.tool.ToolForm.LOCAL, new AgentSendTool().form());
    }

    private JarnessRuntime runtime(String config, ScriptedProvider provider) throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), config);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        runtime.providers().register(provider);
        runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
        return runtime;
    }

    private static boolean hasText(Message message, String text) {
        return message.content().stream()
                .anyMatch(block -> block instanceof ContentBlock.Text content
                        && content.text().contains(text));
    }

    private static void restoreExecutor(String previous) {
        if (previous == null) {
            System.clearProperty("jarness.executor");
        } else {
            System.setProperty("jarness.executor", previous);
        }
    }
}
