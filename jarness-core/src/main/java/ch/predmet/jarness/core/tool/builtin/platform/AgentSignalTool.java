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

/** Platform tool: deliver a control signal. */
public final class AgentSignalTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating("agent_signal", "Deliver a control signal to an agent.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("pid", ToolParameter.required("integer", "Agent pid."));
        params.put("signal", ToolParameter.required("string", "PAUSE, RESUME, INTERRUPT, or STOP."));
        params.put("subtree", ToolParameter.optional("boolean", "Deliver to descendants too. Defaults to false."));
        return params;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        long pid = invocation.integer("pid", -1);
        AgentSignal signal;
        try {
            signal = AgentSignal.valueOf(invocation.requireString("signal").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolOutcome.error("unknown signal; expected PAUSE, RESUME, INTERRUPT, or STOP");
        }
        boolean ok = ctx.runtime().supervisor().signal(pid, signal, invocation.bool("subtree", false));
        return ok ? ToolOutcome.ok("signalled pid " + pid + " with " + signal)
                : ToolOutcome.error("unknown pid " + pid);
    }
}
