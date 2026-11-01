package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.provider.ModelRef;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jarness run}: one-shot prompt against a fresh agent.
 */
@Command(name = "run", description = "Run one prompt through an agent and print its answer.")
public final class RunCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0..*", arity = "0..*", description = "The prompt (joined with spaces).")
    List<String> promptParts = List.of();

    @Option(names = {"-p", "--prompt"}, description = "The prompt text.")
    String prompt;

    @Option(names = {"-t", "--template"}, description = "Agent template. Default: the configured default template.")
    String template;

    @Option(names = {"-m", "--model"}, description = "Model selector (provider/model or model).")
    String model;

    @Option(names = {"-n", "--name"}, description = "Agent name.")
    String name;

    @Option(names = {"-w", "--workspace"}, description = "Agent working directory. Default: a fresh one under the base dir.")
    Path workspace;

    @Override
    public Integer call() {
        String text = prompt != null ? prompt : String.join(" ", promptParts);
        if (text.isBlank()) {
            System.err.println("jarness run: a prompt is required (positional or --prompt)");
            return 2;
        }
        try (JarnessRuntime runtime = Runtimes.build(spec)) {
            runtime.start();
            String templateId = template != null ? template : runtime.config().defaultTemplate();
            AgentRequest.Builder builder = AgentRequest.builder(templateId).name(name).workspace(workspace);
            Main main = (Main) spec.root().userObject();
            if (main.global().namespace != null) {
                builder.namespace(main.global().namespace);
            }
            if (model != null) {
                builder.model(ModelRef.parse(model));
            }
            Agent agent = runtime.spawn(builder.build());
            TurnResult result = agent.submit(text).join();
            if (result.ok()) {
                System.out.println(result.output());
                return 0;
            }
            System.err.println("turn " + result.state() + ": " + result.error());
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
