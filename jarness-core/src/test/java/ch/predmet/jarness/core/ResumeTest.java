package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentState;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.kernel.ImageRef;
import ch.predmet.jarness.api.session.ImageMetadata;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.ResumeDeniedException;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.SessionInit;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resume and checkpoint behavior against a live runtime.
 */
class ResumeTest {

    @TempDir
    Path base;

    private JarnessRuntime runtime() throws IOException {
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

    private Session image(JarnessRuntime runtime, List<String> tools, String... missingMods) {
        Session session = runtime.sessions().create(new SessionInit(null, "agent-image", null, base,
                "void", null, Map.of()));
        List<Map<String, Object>> mods = java.util.Arrays.stream(missingMods)
                .map(id -> Map.<String, Object>of("id", id, "version", "1"))
                .toList();
        ImageMetadata image = new ImageMetadata("agent-image", session.id(), "default",
                ImageMetadata.SCHEMA_VERSION, -1,
                Map.of("template", Map.of("id", "void", "version", "1"),
                        "loop", "default",
                        "tools", tools,
                        "mods", mods),
                Budget.of(7, Instant.now().plusSeconds(3600), 3, 2),
                CapabilitySet.of(Capability.of(CapKind.FS_READ)),
                AgentState.IDLE.name(), Instant.now());
        runtime.sessions().saveImage(image);
        return session;
    }

    @Test
    void resumeIsRefusedWhenDependenciesChanged() throws IOException {
        try (JarnessRuntime runtime = runtime()) {
            Session session = image(runtime, List.of("read"), "ghost.mod");

            ResumeDeniedException denied = assertThrows(ResumeDeniedException.class,
                    () -> runtime.spawn(AgentRequest.builder("void").sessionId(session.id()).build()));
            assertTrue(denied.getMessage().contains("ghost.mod"), denied.getMessage());
            assertTrue(runtime.supervisor().superAgent().isPresent());
            assertEquals(1, runtime.supervisor().size(), "only the super agent exists");
        }
    }

    @Test
    void forcedResumeDegradesAndRestoresBudgetAndCapabilities() throws IOException {
        try (JarnessRuntime runtime = runtime()) {
            Session session = image(runtime, List.of("read"), "ghost.mod");

            Agent agent = runtime.spawn(AgentRequest.builder("void")
                    .sessionId(session.id())
                    .forceResume(true)
                    .build());

            assertEquals(session.id(), agent.session().id());
            assertEquals(AgentState.IDLE, agent.state());
            assertEquals(7, agent.budget().tokensLeft());
            assertTrue(agent.capabilities().containsKind(CapKind.FS_READ));
            assertFalse(agent.capabilities().containsKind(CapKind.FS_WRITE));
            assertFalse(agent.ctx().visibleToolNames().contains("ghost"));
        }
    }

    @Test
    void supervisorResumeAndCheckpoint() throws IOException {
        try (JarnessRuntime runtime = runtime()) {
            Session session = image(runtime, List.of());
            Agent agent = runtime.supervisor().resume(new ImageRef(session.id(), "default", -1));
            assertEquals(session.id(), agent.session().id());
            assertEquals(7, agent.budget().tokensLeft());

            // Fabricate a committed turn without a model, then checkpoint through the supervisor.
            Turn turn = agent.session().startTurn("hello");
            agent.session().append(Message.user("hello"));
            turn.finish(TurnState.COMPLETED, "hi", null);
            agent.session().commitTurn(turn, null);

            ImageRef ref = runtime.supervisor().checkpoint(agent.pid());
            assertEquals(0, ref.checkpointTurn());
            Session reloaded = runtime.sessions().load(session.id()).orElseThrow();
            assertEquals(1, reloaded.turns().size());
            assertEquals(1, reloaded.messages().size());
            assertEquals("hello", reloaded.messages().get(0).text());

            ImageRef suspendedRef = runtime.supervisor().suspend(agent.pid());
            assertEquals(AgentState.SUSPENDED, agent.state());
            Agent resumed = runtime.supervisor().resume(suspendedRef);
            assertTrue(resumed.pid() != agent.pid());
            assertEquals(session.id(), resumed.session().id());
            assertEquals(1, resumed.session().turns().size());
        }
    }
}
