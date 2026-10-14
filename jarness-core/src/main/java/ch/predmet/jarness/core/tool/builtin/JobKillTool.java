package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code job_kill} — request cancellation of a running background job.
 */
public final class JobKillTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating(
            "job_kill",
            "Request cancellation of a running background job.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("job_id", ToolParameter.required("string", "Job id, or a unique prefix of it."));
        params.put("reason", ToolParameter.optional("string", "Short reason recorded on the job."));
        return params;
    }

    /** Create the tool. */
    public JobKillTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String id = invocation.requireString("job_id");
            String reason = invocation.string("reason", "");
            boolean accepted = ctx.jobs().kill(id, reason);
            if (!accepted) {
                return ToolOutcome.error("no running job matches " + id
                        + " (unknown, ambiguous, or already finished)");
            }
            return ToolOutcome.ok("kill requested for job " + id);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("job_kill failed: " + e.getMessage());
        }
    }
}
