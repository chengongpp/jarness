package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.AgentTemplate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jarness templates}: list registered agent templates.
 */
@Command(name = "templates", description = "List the agent templates registered under the preset.")
public final class TemplatesCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try (JarnessRuntime runtime = Runtimes.build(spec)) {
            runtime.start();
            for (AgentTemplate template : runtime.templates().all()) {
                System.out.printf("%-10s %-10s %s%n", template.id(),
                        template.dynamicMods() ? "[dynmods]" : "", template.description());
                if (!template.toolNames().isEmpty()) {
                    System.out.println("    tools: " + String.join(", ", template.toolNames()));
                }
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }
}
