package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.agent.AgentSignal;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Platform tool: kill an agent or its subtree. */
public final class AgentKillTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating("agent_kill", "Kill an agent, optionally its whole subtree.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("pid", ToolParameter.required("integer", "Agent pid."));
        params.put("subtree", ToolParameter.optional("boolean", "Kill descendants too. Defaults to true."));
        return params;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        long pid = invocation.integer("pid", -1);
        if (pid < 0) {
            return ToolOutcome.error("missing required parameter \"pid\"");
        }
        boolean subtree = invocation.bool("subtree", true);
        boolean ok = ctx.runtime().supervisor().signal(pid, AgentSignal.STOP, subtree);
        return ok ? ToolOutcome.ok("killed pid " + pid + (subtree ? " (subtree)" : ""))
                : ToolOutcome.error("unknown pid " + pid);
    }
}
