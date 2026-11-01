package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.core.Jarness;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies that relay mode wires executor-backed tools and that the provider is alive. */
class RelayToolTest {

    @TempDir
    Path base;

    @Test
    void relayModeRegistersExecutorTools() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path binary = repo.resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        String previous = System.getProperty("jarness.executor");
        System.setProperty("jarness.executor", binary.toString());
        try {
            JarnessLayout layout = JarnessLayout.of(base);
            Files.createDirectories(layout.configDir());
            Files.writeString(layout.configFile(), """
                    [defaults]
                    template = "void"

                    [execution]
                    mode = "relay"
                    """);
            try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
                runtime.start();
                Tool read = runtime.tools().require("read");
                assertEquals(ToolForm.RELAY, read.form());
                assertEquals("fs.read", read.relayName());
                assertEquals(ToolForm.RELAY, runtime.tools().require("bash").form());
                assertTrue(runtime.execution().healthy());
            }
        } finally {
            if (previous == null) {
                System.clearProperty("jarness.executor");
            } else {
                System.setProperty("jarness.executor", previous);
            }
        }
    }
}
