package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.session.ImageMetadata;
import ch.predmet.jarness.api.session.SessionSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jarness sessions}: list session images, optionally with their dependency manifest and
 * remaining budget.
 */
@Command(name = "sessions", description = "List session images (turns, budget, checkpoint, deps).")
public final class SessionsCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Spec
    CommandSpec spec;

    @Option(names = {"-n", "--limit"}, description = "Maximum sessions to list. Default 20.")
    int limit = 20;

    @Option(names = {"-a", "--agent"}, description = "Only sessions of this agent id.")
    String agent;

    @Option(names = {"-i", "--image"}, description = "Show image metadata (checkpoint, budget, deps).")
    boolean image;

    @Override
    public Integer call() {
        try (JarnessRuntime runtime = Runtimes.build(spec)) {
            runtime.start();
            List<SessionSummary> sessions = agent == null
                    ? runtime.sessions().list(limit)
                    : runtime.sessions().listByAgent(agent);
            if (sessions.isEmpty()) {
                System.out.println("no sessions");
                return 0;
            }
            for (SessionSummary session : sessions) {
                System.out.printf("%-38s %-12s %-16s turns=%-3d msgs=%-3d %s%n",
                        session.id(), session.agentId(), session.title(), session.turnCount(),
                        session.messageCount(), TIME.format(session.updatedAt()));
                if (image) {
                    runtime.sessions().image(session.id()).ifPresent(metadata -> printImage(metadata));
                }
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }

    private static void printImage(ImageMetadata metadata) {
        System.out.printf("    checkpoint=%-3d state=%-8s budget=[%s] caps=%s%n",
                metadata.checkpointTurn(), metadata.state(), metadata.budget().describe(),
                metadata.capabilities().kinds());
        System.out.println("    deps=" + metadata.deps());
    }
}
