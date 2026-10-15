package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.core.Jarness;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jarness tui}: start the runtime with the {@code tui} preset and serve the interactive
 * terminal UI until the user quits.
 */
@Command(name = "tui", description = "Start the interactive terminal UI (activates the tui preset by default).")
public final class TuiCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        Main main = (Main) spec.root().userObject();
        // The TUI lives in the jarness.tui mod and is enabled by the tui preset.
        Jarness.Builder builder = main.global().baseDir != null
                ? Jarness.Builder.at(main.global().baseDir)
                : Jarness.builder();
        builder.preset(main.global().preset != null ? main.global().preset : "tui");
        try (JarnessRuntime runtime = builder.build()) {
            runtime.start();
            if (!runtime.instruments().isEnabled("tui.frontend")) {
                System.err.println("jarness tui: the active preset does not enable jarness.tui:tui.frontend");
                return 1;
            }
            while (runtime.isRunning()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 130;
                }
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }
}
