package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.exec.ExecutionProvider;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;

/** Platform tool: daemon status. */
public final class DaemonStatusTool implements Tool {

    private final ToolSpec spec = ToolSpec.of("daemon_status",
            "Show runtime status: version, agents, namespaces, executors and jobs.");

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        var runtime = ctx.runtime();
        StringBuilder out = new StringBuilder();
        out.append("jarness ").append(runtime.version()).append('\n');
        out.append("agents: ").append(runtime.supervisor().size()).append('\n');
        out.append("namespaces: ").append(runtime.namespaces().list().stream()
                .map(n -> n.id()).toList()).append('\n');
        out.append("providers: ").append(runtime.providers().all().stream()
                .map(p -> p.id()).toList()).append('\n');
        out.append("executors:\n");
        for (ExecutionProvider provider : runtime.execution().providers()) {
            out.append("  ").append(provider.id())
                    .append(" kind=").append(provider.kind())
                    .append(" alive=").append(provider.alive())
                    .append(" tools=").append(provider.tools()).append('\n');
        }
        out.append("jobs: ").append(runtime.jobs().list().size()).append('\n');
        out.append("default model: ").append(runtime.providers().defaultModel().map(Object::toString).orElse("<none>"));
        return ToolOutcome.ok(out.toString());
    }
}
