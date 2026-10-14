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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code subagent} — delegate a self-contained task to a subagent and wait for its result. The
 * child runs in a fresh {@code subagents/<id>} subdirectory of the parent workspace and is stopped
 * after its turn. Nesting is capped through the {@code subagent.depth} agent-context key.
 */
public final class SubagentTool implements Tool {

    private static final long AWAIT_MINUTES = 10;

    private final ToolSpec spec = ToolSpec.mutating(
            "subagent",
            "Delegate a self-contained task to a subagent and wait for its result.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("description", ToolParameter.required("string", "Short (3-5 word) description of the delegated task."));
        params.put("prompt", ToolParameter.required("string", "The complete, self-contained task for the subagent."));
        params.put("template_id", ToolParameter.optional("string",
                "Template for the child agent. Defaults to standard, falls back to minimal."));
        return params;
    }

    /** Create the tool. */
    public SubagentTool() {
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
            String description = invocation.requireString("description");
            String prompt = invocation.requireString("prompt");
            String templateId = pickTemplate(invocation, ctx);
            Path workspace = freshChildWorkspace(ctx);
            child = ctx.runtime().spawn(AgentRequest.builder(templateId)
                    .name("sub:" + description)
                    .workspace(workspace)
                    .build());
            child.ctx().put(NestingGuard.KEY, NestingGuard.depth(ctx) + 1);
            CompletableFuture<TurnResult> turn = child.submit(prompt);
            TurnResult result;
            try {
                result = turn.get(AWAIT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                child.signal(AgentSignal.STOP);
                return ToolOutcome.error("subagent timed out");
            }
            if (result.ok()) {
                return ToolOutcome.ok(result.output());
            }
            String error = result.error() == null || result.error().isBlank()
                    ? "subagent turn ended as " + result.state()
                    : result.error();
            return ToolOutcome.error(error);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("subagent failed: " + e.getMessage());
        } finally {
            if (child != null) {
                try {
                    child.stop("subagent call finished");
                } catch (Exception e) {
                    ctx.log().debug("stopping subagent failed: {}", e.getMessage());
                }
            }
        }
    }

    static String pickTemplate(ToolInvocation invocation, ToolContext ctx) {
        String requested = invocation.string("template_id", "");
        if (!requested.isBlank()) {
            return requested;
        }
        return ctx.runtime().templates().contains("standard") ? "standard" : "minimal";
    }

    static Path freshChildWorkspace(ToolContext ctx) throws java.io.IOException {
        Path dir = ctx.workspace().resolve("subagents")
                .resolve("sub-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        return dir;
    }
}
