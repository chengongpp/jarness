package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.TurnResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jarness super}: one prompt to the platform super agent at pid 0.
 *
 * <p>The super agent is created lazily; this command is the explicit way to reach it without
 * spawning an ordinary root agent first.</p>
 */
@Command(name = "super", description = "Run one prompt as the platform super agent (pid 0).")
public final class SuperCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0..*", arity = "0..*", description = "The prompt (joined with spaces).")
    List<String> promptParts = List.of();

    @Option(names = {"-p", "--prompt"}, description = "The prompt text.")
    String prompt;

    @Override
    public Integer call() {
        String text = prompt != null ? prompt : String.join(" ", promptParts);
        if (text.isBlank()) {
            System.err.println("jarness super: a prompt is required (positional or --prompt)");
            return 2;
        }
        try (JarnessRuntime runtime = Runtimes.build(spec)) {
            runtime.start();
            Agent agent = runtime.supervisor().ensureSuperAgent();
            TurnResult result = agent.submit(text).join();
            if (result.ok()) {
                System.out.println(result.output());
                return 0;
            }
            System.err.println("super turn " + result.state() + ": " + result.error());
            if (!result.output().isBlank()) {
                System.out.println(result.output());
            }
            return 1;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }
}
