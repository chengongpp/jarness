package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.provider.Provider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jarness providers}: list configured provider profiles and models.
 */
@Command(name = "providers", description = "List configured provider profiles and their models.")
public final class ProvidersCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try (JarnessRuntime runtime = Runtimes.build(spec)) {
            runtime.start();
            for (Provider provider : runtime.providers().all()) {
                System.out.printf("%-12s %-16s %s%n", provider.id(), provider.kind().id(),
                        provider.profile().baseUrl());
                provider.models().forEach(m -> System.out.printf("    %-28s ctx=%-8d max=%-8d %s%n",
                        m.id(), m.contextWindow(), m.defaultMaxTokens(), m.name()));
            }
            runtime.providers().defaultModel().ifPresentOrElse(
                    ref -> System.out.println("default model: " + ref),
                    () -> System.out.println("default model: <none>"));
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }
}
