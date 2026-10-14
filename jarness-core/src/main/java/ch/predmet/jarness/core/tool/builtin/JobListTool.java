package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.job.JobStatus;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * {@code job_list} — list background jobs (running and finished) with their ids, kinds, and
 * statuses, newest first.
 */
public final class JobListTool implements Tool {

    private final ToolSpec spec = new ToolSpec(
            "job_list",
            "List background jobs (running and finished) with their ids, kinds, and statuses.",
            Map.of(),
            true);

    /** Create the tool. */
    public JobListTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            List<JobStatus> jobs = ctx.jobs().list();
            if (jobs.isEmpty()) {
                return ToolOutcome.ok("no background jobs");
            }
            StringBuilder out = new StringBuilder();
            for (JobStatus job : jobs) {
                out.append(job.id()).append("  ").append(job.state());
                if (job.exitCode() != -1) {
                    out.append("  exit=").append(job.exitCode());
                }
                out.append("  ").append(job.label()).append('\n');
            }
            out.append('(').append(jobs.size()).append(jobs.size() == 1 ? " job" : " jobs")
                    .append(", newest first)");
            return ToolOutcome.ok(out.toString());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("job_list failed: " + e.getMessage());
        }
    }
}
