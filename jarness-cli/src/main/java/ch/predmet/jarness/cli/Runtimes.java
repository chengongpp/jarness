package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.core.Jarness;
import picocli.CommandLine;

/** Shared helpers for subcommands. */
final class Runtimes {

    private Runtimes() {
    }

    /**
     * Build a runtime honouring the global options of the running command.
     *
     * @param spec the picocli command spec of the subcommand (to reach the root's globals)
     * @return the unstarted runtime
     */
    static JarnessRuntime build(CommandLine.Model.CommandSpec spec) {
        Main main = (Main) spec.root().userObject();
        Jarness.Builder builder = main.global().baseDir != null
                ? Jarness.Builder.at(main.global().baseDir)
                : Jarness.builder();
        if (main.global().preset != null) {
            builder.preset(main.global().preset);
        }
        return builder.build();
    }

    /** @return the layout honouring the global options. */
    static JarnessLayout layout(CommandLine.Model.CommandSpec spec) {
        Main main = (Main) spec.root().userObject();
        return main.global().baseDir != null
                ? JarnessLayout.of(main.global().baseDir)
                : JarnessLayout.defaultLayout();
    }
}
