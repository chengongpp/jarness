package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.job.JobStatus;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code job_output} — read a background job: output since the previous read, or the result of a
 * finished job.
 */
public final class JobOutputTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final long MAX_TIMEOUT_MS = 120_000;

    private final ToolSpec spec = ToolSpec.readOnly(
            "job_output",
            "Read a background job: output since the previous read, or the result of a finished job.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("job_id", ToolParameter.required("string", "Job id, or a unique prefix of it."));
        params.put("wait", ToolParameter.optional("boolean", "Block until the job finishes or timeout_ms expires."));
        params.put("timeout_ms", ToolParameter.optional("integer", "Max wait in milliseconds. Defaults to 30000, cap 120000."));
        return params;
    }

    /** Create the tool. */
    public JobOutputTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String id = invocation.requireString("job_id");
            JobRegistry jobs = ctx.jobs();
            Optional<JobStatus> status = jobs.status(id);
            if (status.isEmpty()) {
                throw new ToolException("unknown or ambiguous job id: " + id);
            }
            if (invocation.bool("wait", false) && status.get().running()) {
                long timeoutMs = Math.max(1, Math.min(invocation.integer("timeout_ms", (int) DEFAULT_TIMEOUT_MS),
                        MAX_TIMEOUT_MS));
                long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
                while (status.get().running() && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    status = jobs.status(id);
                    if (status.isEmpty()) {
                        break;
                    }
                }
            }
            String output = jobs.read(id).orElse("");
            JobStatus snapshot = status.get();
            StringBuilder out = new StringBuilder();
            out.append(snapshot.id()).append(" [").append(snapshot.label()).append("] — ")
                    .append(snapshot.state());
            if (snapshot.exitCode() != -1) {
                out.append(" (exit code ").append(snapshot.exitCode()).append(')');
            }
            out.append('\n');
            out.append(output.isEmpty() ? "(no new output)" : stripTrailingNewline(output));
            return ToolOutcome.ok(out.toString());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("job_output failed: " + e.getMessage());
        }
    }

    private static String stripTrailingNewline(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }
}
