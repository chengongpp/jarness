package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.Map;

/** Platform tool: remove a namespace. */
public final class NsRemoveTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating("ns_remove", "Remove a namespace that has no live agents.",
            Map.of("id", ToolParameter.required("string", "Namespace id.")));

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        String id = invocation.requireString("id");
        boolean removed = ctx.runtime().supervisor().removeNamespace(id);
        return ToolOutcome.ok(removed ? "removed namespace " + id : "no namespace " + id);
    }
}
