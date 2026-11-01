package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentSignal;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code agent_spawn} — create an agent from a registered template, optionally with an initial
 * prompt that this call waits on (up to 10 minutes). Same nesting guard as {@code subagent}.
 */
public final class AgentSpawnTool implements Tool {

    private static final long AWAIT_MINUTES = 10;

    private final ToolSpec spec = ToolSpec.mutating(
            "agent_spawn",
            "Create an agent from a registered template, optionally with an initial prompt.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("template_id", ToolParameter.required("string", "Registered template id to create the agent from."));
        params.put("name", ToolParameter.optional("string", "Agent name; derived when absent."));
        params.put("prompt", ToolParameter.optional("string",
                "When given, submitted immediately and the call waits for the result (up to 10 minutes)."));
        return params;
    }

    /** Create the tool. */
    public AgentSpawnTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        if (!NestingGuard.allowed(ctx)) {
            return ToolOutcome.denied("subagent nesting limit reached");
        }
        Agent child = null;
        try {
            String templateId = invocation.requireString("template_id");
            String name = invocation.string("name", null);
            String prompt = invocation.string("prompt", null);
            AgentRequest.Builder request = AgentRequest.builder(templateId);
            if (name != null && !name.isBlank()) {
                request.name(name);
            }
            child = ctx.runtime().spawnFrom(ctx.agent(), request.build());
            child.ctx().put(NestingGuard.KEY, NestingGuard.depth(ctx) + 1);
            StringBuilder out = new StringBuilder();
            out.append("spawned agent ").append(child.id())
                    .append(" (").append(child.name()).append(", template ").append(templateId).append(')');
            if (prompt == null || prompt.isBlank()) {
                return ToolOutcome.ok(out.toString());
            }
            try {
                TurnResult result = child.submit(prompt).get(AWAIT_MINUTES, TimeUnit.MINUTES);
                out.append('\n');
                if (result.ok()) {
                    out.append(result.output());
                } else {
                    String error = result.error() == null || result.error().isBlank()
                            ? "agent turn ended as " + result.state()
                            : result.error();
                    return ToolOutcome.error(error, out + "\n" + error);
                }
                return ToolOutcome.ok(out.toString());
            } catch (TimeoutException e) {
                child.signal(AgentSignal.STOP);
                return ToolOutcome.error("spawned agent " + child.id() + " timed out after "
                        + AWAIT_MINUTES + " minutes");
            }
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("agent_spawn failed: " + e.getMessage());
        }
    }
}
