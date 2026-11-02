package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.provider.ProviderProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test against the live endpoint described by {@code tmp/endpoint.txt}.
 *
 * <p>Runs only with {@code -Djarness.it=true}; excluded from the default build via the
 * {@code integration} tag.</p>
 */
@Tag("integration")
class LiveEndpointIntegrationTest {

    @TempDir
    Path base;

    private JarnessRuntime runtime(ProviderProfile profile) {
        // materialize a providers.toml for the profile into the temp base dir
        StringBuilder toml = new StringBuilder();
        toml.append("[profiles.").append(profile.id()).append("]\n");
        toml.append("name = \"").append(profile.name()).append("\"\n");
        toml.append("type = \"").append(profile.kind().id()).append("\"\n");
        toml.append("base_url = \"").append(profile.baseUrl()).append("\"\n");
        toml.append("api_key = \"").append(profile.apiKey()).append("\"\n");
        for (var model : profile.models()) {
            toml.append("[[profiles.").append(profile.id()).append(".models]]\n");
            toml.append("id = \"").append(model.id()).append("\"\n");
            toml.append("name = \"").append(model.name()).append("\"\n");
            toml.append("context_window = ").append(model.contextWindow()).append("\n");
            toml.append("default_max_tokens = ").append(model.defaultMaxTokens()).append("\n");
        }
        try {
            Files.createDirectories(JarnessLayout.of(base).configDir());
            Files.writeString(JarnessLayout.of(base).providersFile(), toml.toString());
            Files.writeString(JarnessLayout.of(base).configFile(),
                    "[defaults]\nprovider = \"" + profile.id() + "\"\nmodel = \""
                            + profile.firstModelId() + "\"\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }

    @Test
    void plainTurnAnswers() throws Exception {
        assumeTrue(Boolean.getBoolean("jarness.it"), "integration tests disabled");
        try (JarnessRuntime rt = runtime(TestEndpoints.gb10())) {
            assertTrue(rt.modLoader().baked(), () -> rt.modLoader().lastReport().render());
            Agent agent = rt.spawn(AgentRequest.builder("void").name("it-void").build());
            TurnResult result = agent.submit("Reply with exactly: PONG").get();
            assertEquals(ch.predmet.jarness.api.session.TurnState.COMPLETED, result.state(),
                    result.error());
            assertTrue(result.output().contains("PONG"), result.output());
            assertTrue(result.promptTokens() > 0);
        }
    }

    @Test
    void standardAgentUsesTools() throws Exception {
        assumeTrue(Boolean.getBoolean("jarness.it"), "integration tests disabled");
        try (JarnessRuntime rt = runtime(TestEndpoints.gb10())) {
            Agent agent = rt.spawn(AgentRequest.builder("standard").name("it-std").build());
            TurnResult result = agent.submit(
                    "Create a file named hello.txt containing the text \"jarness works\", "
                            + "then read it back and tell me its exact content.").get();
            assertEquals(ch.predmet.jarness.api.session.TurnState.COMPLETED, result.state(),
                    result.error());
            assertTrue(Files.readString(agent.workspace().resolve("hello.txt")).contains("jarness works"));
            assertTrue(result.output().contains("jarness works"), result.output());
            assertTrue(result.steps() >= 2, "expected at least one tool step, got " + result.steps());
            // the session persisted the tool calls
            assertTrue(agent.session().messages().stream().anyMatch(m -> !m.toolResults().isEmpty()));
        }
    }

    @Test
    void supervisorTracksAgents() throws Exception {
        assumeTrue(Boolean.getBoolean("jarness.it"), "integration tests disabled");
        try (JarnessRuntime rt = runtime(TestEndpoints.gb10())) {
            Agent a = rt.spawn(AgentRequest.builder("void").name("a").build());
            Agent b = rt.spawn(AgentRequest.builder("void").name("b").build());
            assertEquals(3, rt.supervisor().size(), "super agent + a + b");
            assertTrue(rt.supervisor().status(a.id()).isPresent());
            TurnResult ra = a.submit("Say A").get();
            assertTrue(ra.ok(), ra.error());
            rt.supervisor().signal(b.id(), ch.predmet.jarness.api.agent.AgentSignal.STOP);
            assertTrue(b.awaitIdle(Duration.ofSeconds(5)));
            rt.supervisor().unregister(b.id());
            assertEquals(2, rt.supervisor().size(), "super agent + a");
        }
    }

    @Test
    void resolvesModelRefs() {
        assumeTrue(Boolean.getBoolean("jarness.it"), "integration tests disabled");
        ProviderProfile profile = TestEndpoints.gb10();
        try (JarnessRuntime rt = runtime(profile)) {
            ModelRef ref = rt.providers().resolveSelector(null);
            assertEquals(profile.id(), ref.providerId());
            assertEquals(profile.firstModelId(), ref.modelId());
        }
    }
}
