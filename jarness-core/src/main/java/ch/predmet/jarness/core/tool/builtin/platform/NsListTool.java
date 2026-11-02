package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;

/** Platform tool: list namespaces. */
public final class NsListTool implements Tool {

    private final ToolSpec spec = ToolSpec.of("ns_list", "List namespaces with template, model and capability ceiling.");

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        StringBuilder out = new StringBuilder();
        for (Namespace ns : ctx.runtime().namespaces().list()) {
            out.append(ns.id())
                    .append(ns.isDefault() ? " (default)" : "")
                    .append("  template=").append(ns.defaultTemplateId())
                    .append("  cwd=").append(ns.cwd())
                    .append("  caps=").append(ns.metadata().defaults().kinds())
                    .append('\n');
        }
        return ToolOutcome.ok(out.toString().stripTrailing());
    }
}
