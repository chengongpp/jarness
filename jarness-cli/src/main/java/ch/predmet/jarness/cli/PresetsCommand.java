package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.preset.Preset;
import ch.predmet.jarness.core.config.PresetLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jarness presets}: list available presets.
 */
@Command(name = "presets", description = "List available presets (builtin and from <base>/presets).")
public final class PresetsCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        JarnessLayout layout = Runtimes.layout(spec);
        for (Preset preset : PresetLoader.all(layout)) {
            System.out.printf("%-12s %-8s %s%n", preset.name(), preset.builtin() ? "builtin" : "user",
                    preset.description());
            if (!preset.mods().isEmpty()) {
                System.out.println("    mods       : " + preset.mods());
            }
            if (!preset.instruments().isEmpty()) {
                preset.instruments().forEach((k, v) ->
                        System.out.println("    instrument : " + k + " = " + v));
            }
            if (preset.template() != null) {
                System.out.println("    template   : " + preset.template());
            }
        }
        return 0;
    }
}
