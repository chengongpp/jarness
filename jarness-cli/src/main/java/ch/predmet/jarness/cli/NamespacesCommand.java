package ch.predmet.jarness.cli;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.namespace.ModelProfile;
import ch.predmet.jarness.api.namespace.Namespace;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jarness namespaces}: list the namespaces the runtime will spawn into, with the capability
 * ceiling, model profile, and default template of each.
 */
@Command(name = "namespaces", description = "List namespaces (cwd, capabilities, model profile, default template).")
public final class NamespacesCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try (JarnessRuntime runtime = Runtimes.build(spec)) {
            runtime.start();
            for (Namespace namespace : runtime.namespaces().list()) {
                String template = namespace.defaultTemplateId() == null ? "<none>" : namespace.defaultTemplateId();
                boolean known = namespace.defaultTemplateId() != null
                        && runtime.templates().contains(namespace.defaultTemplateId());
                ModelProfile model = namespace.metadata().model();
                System.out.printf("%-12s %-8s template=%-14s cwd=%-28s model=%-32s %s%n",
                        namespace.id(),
                        namespace.isDefault() ? "default" : "",
                        template + (known ? "" : " (missing)"),
                        namespace.cwd(),
                        model == null ? "<runtime default>" : model.model().toString(),
                        namespace.metadata().defaults().kinds());
                if (!namespace.metadata().labels().isEmpty()) {
                    System.out.println("    labels     : " + namespace.metadata().labels());
                }
                if (!namespace.metadata().policies().values().isEmpty()) {
                    System.out.println("    policies   : " + namespace.metadata().policies().values());
                }
            }
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jarness: " + e.getMessage());
            return 1;
        }
    }
}
