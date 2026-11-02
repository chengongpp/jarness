package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.config.JarnessLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Preset defaults overlay config before namespaces and model defaults are assembled. */
class PresetOverlayTest {

    @TempDir
    Path base;

    @Test
    void presetTemplateBecomesTheNamespaceDefault() throws Exception {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.createDirectories(layout.presetsDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [execution]
                mode = "local"
                """);
        Files.writeString(layout.presetFile("reader"), """
                name = "reader"

                [defaults]
                template = "minimal"
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).preset("reader").build()) {
            runtime.start();
            assertEquals("reader", runtime.activePreset().name());
            assertEquals("minimal", runtime.namespaces().defaultNs().defaultTemplateId());
        }
    }
}
