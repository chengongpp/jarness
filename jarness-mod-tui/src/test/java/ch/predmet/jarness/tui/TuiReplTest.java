package ch.predmet.jarness.tui;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.core.Jarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Initial spawn failure is a terminal UI event, not a permanently running headless runtime. */
class TuiReplTest {

    @TempDir
    Path base;

    @Test
    void initialSpawnFailureClosesRuntime() throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "does-not-exist"

                [execution]
                mode = "local"
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        try {
            runtime.start();
            new TuiRepl(runtime).run();
            assertFalse(runtime.isRunning());
        } finally {
            runtime.close();
        }
    }
}
