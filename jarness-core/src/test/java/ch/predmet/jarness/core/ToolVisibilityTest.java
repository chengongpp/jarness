package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.SimpleAgentTemplate;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.core.exec.ScriptedProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model-facing tool list is template visibility intersected with current capabilities. */
class ToolVisibilityTest {

    @TempDir
    Path base;

    @Test
    void unauthorizedToolsAreNotAdvertisedAndCannotRun() throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [execution]
                mode = "local"
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            ScriptedProvider provider = new ScriptedProvider();
            runtime.providers().register(provider);
            runtime.providers().setDefault(new ModelRef(ScriptedProvider.ID, ScriptedProvider.MODEL));
            Agent agent = runtime.spawn(AgentRequest.builder(SimpleAgentTemplate.builder("reader")
                            .name("Reader").tools("read", "write").build())
                    .capabilities(CapabilitySet.of(Capability.of(CapKind.FS_READ)))
                    .build());

            assertTrue(agent.submit("try writing").get(10, TimeUnit.SECONDS).ok());
            assertEquals(java.util.List.of("read"), provider.lastRequest().tools().stream()
                    .map(tool -> tool.name()).toList());
            assertTrue(agent.session().messages().stream().flatMap(m -> m.toolResults().stream())
                    .anyMatch(result -> result.text().contains("capability denied")));
        }
    }
}
