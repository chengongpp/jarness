package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.session.ImageMetadata;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.session.SessionSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** {@code jarness sessions}: list images from the selected namespace, or all namespace stores. */
@Command(name = "sessions", description = "List session images (turns, budget, checkpoint, deps).")
public final class SessionsCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private record LocatedSession(String namespace, SessionStore store, SessionSummary summary) {
    }

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
            String selected = ((Main) spec.root().userObject()).global().namespace;
            List<LocatedSession> sessions = locateSessions(runtime, selected);
            if (sessions.isEmpty()) {
                System.out.println("no sessions");
                return 0;
            }
            for (LocatedSession located : sessions) {
                SessionSummary session = located.summary();
                System.out.printf("%-38s ns=%-12s %-12s %-16s turns=%-3d msgs=%-3d %s%n",
                        session.id(), located.namespace(), session.agentId(), session.title(),
                        session.turnCount(), session.messageCount(), TIME.format(session.updatedAt()));
                if (image) {
                    located.store().image(session.id()).ifPresent(SessionsCommand::printImage);
                }
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }

    private List<LocatedSession> locateSessions(JarnessRuntime runtime, String selected) {
        List<LocatedSession> found = new ArrayList<>();
        int effectiveLimit = Math.max(0, limit);
        Set<SessionStore> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Namespace namespace : runtime.namespaces().list()) {
            if (selected != null && !selected.equals(namespace.id())) {
                continue;
            }
            SessionStore store = namespace.store();
            if (!visited.add(store)) {
                continue;
            }
            List<SessionSummary> summaries = agent == null
                    ? store.list(effectiveLimit) : store.listByAgent(agent);
            for (SessionSummary summary : summaries) {
                String ownerNamespace = store.image(summary.id()).map(ImageMetadata::namespace)
                        .orElse(namespace.id());
                found.add(new LocatedSession(ownerNamespace, store, summary));
            }
        }
        found.sort(Comparator.comparing((LocatedSession located) -> located.summary().updatedAt()).reversed());
        return found.size() <= effectiveLimit ? found : List.copyOf(found.subList(0, effectiveLimit));
    }

    private static void printImage(ImageMetadata metadata) {
        System.out.printf("    checkpoint=%-3d state=%-8s budget=[%s] caps=%s%n",
                metadata.checkpointTurn(), metadata.state(), metadata.budget().describe(),
                metadata.capabilities().kinds());
        System.out.println("    deps=" + metadata.deps());
    }
}
