package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.kernel.AgentTree;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;

/** Platform tool: render the supervision tree. */
public final class AgentTreeTool implements Tool {

    private final ToolSpec spec = ToolSpec.of("agent_tree", "Render the supervision tree from the super agent.");

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        AgentTree root = ctx.runtime().supervisor().tree(0);
        StringBuilder out = new StringBuilder();
        render(root, "", out);
        return ToolOutcome.ok(out.toString().stripTrailing());
    }

    private static void render(AgentTree node, String indent, StringBuilder out) {
        out.append(indent).append(node.pid()).append(' ').append(node.name())
                .append(" ns=").append(node.namespaceId())
                .append(" ").append(node.state()).append('\n');
        for (AgentTree child : node.children()) {
            render(child, indent + "  ", out);
        }
    }
}
