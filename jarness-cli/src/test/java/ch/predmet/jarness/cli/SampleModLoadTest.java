package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.core.Jarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the packaged sample mod loads into a running runtime, both through {@code mods/}
 * discovery and through the dynamic {@code loadJar} path.
 */
class SampleModLoadTest {

    @TempDir
    Path base;

    private Path sampleJar() {
        Path jar = Path.of(System.getProperty("jarness.sampleModJar", ""));
        assumeTrue(Files.isRegularFile(jar), "sample mod jar not built: " + jar);
        return jar;
    }

    @Test
    void loadsFromModsDirectory() throws Exception {
        Files.createDirectories(base.resolve("mods"));
        Files.copy(sampleJar(), base.resolve("mods").resolve(sampleJar().getFileName()));
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            assertTrue(runtime.modLoader().lastReport().ok(),
                    () -> runtime.modLoader().lastReport().render());
            assertTrue(runtime.modLoader().loaded().stream()
                    .anyMatch(m -> m.descriptor().id().equals("jarness.sample")));
            assertTrue(runtime.tools().contains("sample_echo"));
        }
    }

    @Test
    void loadsDynamically() {
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            LoadReport report = runtime.modLoader().loadJar(sampleJar(), true);
            assertTrue(report.ok(), report::render);
            assertTrue(runtime.tools().contains("sample_echo"));
            assertTrue(runtime.instruments().byName("echo-tool").isPresent());
        }
    }
}
