package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.instrument.Instrument;
import ch.predmet.jarness.api.mod.LoadedMod;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jarness mods}: list loaded mods and their instruments.
 */
@Command(name = "mods", description = "List the mods the runtime loads, with their instruments.")
public final class ModsCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try (JarnessRuntime runtime = Runtimes.build(spec)) {
            runtime.start();
            System.out.println("mods (load order):");
            for (LoadedMod mod : runtime.modLoader().loaded()) {
                var d = mod.descriptor();
                System.out.printf("  #%d %-20s %-12s %s%s%n", mod.order(), d.id(), d.version(),
                        d.system() ? "[system] " : "", d.name());
            }
            System.out.println();
            System.out.println("instruments (apply order):");
            for (Instrument instrument : runtime.instruments().all()) {
                System.out.printf("  %-34s %-7s prio=%-5d %s%n", instrument.key(),
                        runtime.instruments().isEnabled(instrument.name()) ? "on" : "off",
                        instrument.priority(), instrument.description());
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }
}
