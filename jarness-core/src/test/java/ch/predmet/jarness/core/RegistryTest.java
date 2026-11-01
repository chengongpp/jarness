package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.registry.Registry;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryTest {

    @TempDir
    Path base;

    @Test
    void snapshotsAreVersionedAndImmutable() throws Exception {
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
            Registry registry = runtime.registry();
            Registry.Snapshot before = registry.snapshot();
            assertTrue(before.tools().containsKey("read"));

            runtime.tools().register(new MarkerTool());
            int afterRegister = registry.version();
            assertNull(before.tool("marker"), "an old snapshot must not see later registrations");
            registry.bump();
            assertTrue(registry.version() > afterRegister);
            Registry.Snapshot after = registry.snapshot();
            assertTrue(after.tool("marker") != null);
            assertEquals(registry.version(), after.version());
        }
    }

    private static final class MarkerTool implements Tool {

        private final ToolSpec spec = ToolSpec.of("marker", "Marker.");

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
            return ToolOutcome.ok("marker");
        }
    }
}
