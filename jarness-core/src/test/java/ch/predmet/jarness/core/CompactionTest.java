package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ChatRequest;
import ch.predmet.jarness.api.provider.ChatResponse;
import ch.predmet.jarness.api.provider.ModelInfo;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.provider.Provider;
import ch.predmet.jarness.api.provider.ProviderKind;
import ch.predmet.jarness.api.provider.ProviderProfile;
import ch.predmet.jarness.api.provider.StreamSink;
import ch.predmet.jarness.api.provider.Usage;
import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Role;
import ch.predmet.jarness.api.session.TurnState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTest {

    @TempDir
    Path base;

    @Test
    void longHistoryIsCompactedOnAUserBoundary() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            CapturingProvider provider = new CapturingProvider();
            runtime.providers().register(provider);
            runtime.providers().setDefault(new ModelRef(CapturingProvider.ID, CapturingProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("void").name("compactor").build());
            for (int i = 0; i < 260; i++) {
                agent.session().append(Message.user("old message " + i));
            }
            TurnResult result = agent.submit("latest question").get(10, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertTrue(provider.lastMessageCount.get() <= 81,
                    "expected the request to be compacted, got " + provider.lastMessageCount.get());
            assertEquals(Role.USER, provider.firstRole);
            assertTrue(provider.firstText.startsWith("[Context compacted:"), provider.firstText);
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

                [limits]
                compact_after_messages = 40
                compact_keep_messages = 20
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }

    private static final class CapturingProvider implements Provider {

        static final String ID = "capture";
        static final String MODEL = "capture-model";

        final AtomicInteger lastMessageCount = new AtomicInteger();
        Role firstRole;
        String firstText = "";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public ProviderKind kind() {
            return ProviderKind.OPENAI_COMPAT;
        }

        @Override
        public ProviderProfile profile() {
            return new ProviderProfile(ID, "Capture", ProviderKind.OPENAI_COMPAT, "http://localhost", "",
                    Map.of(), List.of(ModelInfo.of(MODEL, MODEL, 4096, 1024)), Map.of(), ConfigView.empty());
        }

        @Override
        public List<ModelInfo> models() {
            return profile().models();
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            return complete(request, StreamSink.DISCARD);
        }

        @Override
        public ChatResponse complete(ChatRequest request, StreamSink sink) {
            lastMessageCount.set(request.messages().size());
            if (!request.messages().isEmpty()) {
                firstRole = request.messages().get(0).role();
                firstText = request.messages().get(0).text();
            }
            return new ChatResponse("ok", new Message(null, Role.ASSISTANT,
                    List.of(new ContentBlock.Text("done")), null), new Usage(1, 1, 0), "stop", "");
        }
    }
}
