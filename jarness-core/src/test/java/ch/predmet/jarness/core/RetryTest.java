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
import ch.predmet.jarness.api.provider.ProviderException;
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

/** Provider retries back off on retryable failures and stop on non-retryable ones. */
class RetryTest {

    @TempDir
    Path base;

    @Test
    void retriesRetryableProviderFailures() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            FlakyProvider provider = new FlakyProvider(2, 500);
            runtime.providers().register(provider);
            runtime.providers().setDefault(new ModelRef(FlakyProvider.ID, FlakyProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("void").name("retry").build());
            TurnResult result = agent.submit("hi").get(10, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertEquals(3, provider.calls.get());
        }
    }

    @Test
    void retriesTransportFailuresWithoutAnHttpStatus() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            FlakyProvider provider = new FlakyProvider(2, -1, true);
            runtime.providers().register(provider);
            runtime.providers().setDefault(new ModelRef(FlakyProvider.ID, FlakyProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("void").build());
            TurnResult result = agent.submit("hi").get(10, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state(), result.error());
            assertEquals(3, provider.calls.get());
        }
    }

    @Test
    void doesNotRetryNonRetryableFailures() throws Exception {
        try (JarnessRuntime runtime = runtime()) {
            FlakyProvider provider = new FlakyProvider(5, 400);
            runtime.providers().register(provider);
            runtime.providers().setDefault(new ModelRef(FlakyProvider.ID, FlakyProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder("void").name("retry").build());
            TurnResult result = agent.submit("hi").get(10, TimeUnit.SECONDS);
            assertEquals(TurnState.FAILED, result.state());
            assertEquals(1, provider.calls.get());
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

                [provider]
                retries = 2
                backoff_ms = 10
                backoff_max_ms = 20
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }

    private static final class FlakyProvider implements Provider {

        static final String ID = "flaky";
        static final String MODEL = "flaky-model";

        private final int failures;
        private final int status;
        private final boolean transport;
        final AtomicInteger calls = new AtomicInteger();

        FlakyProvider(int failures, int status) {
            this(failures, status, false);
        }

        FlakyProvider(int failures, int status, boolean transport) {
            this.failures = failures;
            this.status = status;
            this.transport = transport;
        }

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
            return new ProviderProfile(ID, "Flaky", ProviderKind.OPENAI_COMPAT, "http://localhost", "",
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
            int call = calls.incrementAndGet();
            if (call <= failures) {
                if (transport) {
                    throw ProviderException.transport("network timeout", new java.io.IOException("timeout"));
                }
                throw new ProviderException("boom", status, null);
            }
            return new ChatResponse("ok", new Message(null, Role.ASSISTANT,
                    List.of(new ContentBlock.Text("done")), null), new Usage(1, 1, 0), "stop", "");
        }
    }
}
