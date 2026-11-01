package ch.predmet.jarness.cli;

import ch.predmet.jarness.core.Jarness;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * {@code jarness} command line entry point.
 */
@Command(name = "jarness",
        mixinStandardHelpOptions = true,
        version = "jarness " + Jarness.VERSION,
        description = "Jarness multi-agent runtime.",
        subcommands = {
                RunCommand.class,
                TuiCommand.class,
                ModsCommand.class,
                PresetsCommand.class,
                TemplatesCommand.class,
                ProvidersCommand.class,
                NamespacesCommand.class,
                SessionsCommand.class,
                SuperCommand.class
        })
public final class Main implements Runnable {

    /** Global options shared by all subcommands. */
    @Command(scope = CommandLine.ScopeType.INHERIT)
    public static class Global {
        /** Jarness base directory. */
        @Option(names = {"--base-dir"}, description = "Jarness base directory. Default: $HOME/.local/share/jarness")
        public Path baseDir;

        /** Preset to activate. */
        @Option(names = {"--preset"}, description = "Preset to activate. Default: the configured preset.")
        public String preset;

        /** Namespace to spawn agents in. */
        @Option(names = {"-N", "--namespace"}, description = "Namespace to spawn agents in. Default: default.")
        public String namespace;
    }

    @Mixin
    final Global global = new Global();

    /** @return the global options. */
    Global global() {
        return global;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * CLI entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
