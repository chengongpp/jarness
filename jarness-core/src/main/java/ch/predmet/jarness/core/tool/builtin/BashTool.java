package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.config.SandboxMode;
import ch.predmet.jarness.api.job.JobHandle;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code bash} — execute a bash command ({@code bash -c}) and return its stdout/stderr, or hand the
 * process to the job registry when asked to run in the background.
 *
 * <p>Foreground output is stdout, then a {@code [stderr]} section when non-empty, then the
 * mandatory {@code [exit code: N]} marker, truncated to the last ~60000 characters. The working
 * directory must resolve inside the namespace cwd unless the sandbox is
 * {@link SandboxMode#FULL_ACCESS}.</p>
 */
public final class BashTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 120_000;
    private static final long MAX_TIMEOUT_MS = 600_000;
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final ToolSpec spec = ToolSpec.mutating(
            "bash",
            "Execute a bash command (`bash -c`) and return its stdout/stderr. Long output is truncated to its tail.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("command", ToolParameter.required("string", "The bash command to execute."));
        params.put("description", ToolParameter.optional("string",
                "Clear, concise description of what this command does, 5-10 words."));
        params.put("workdir", ToolParameter.optional("string",
                "Working directory. Defaults to the namespace cwd."));
        params.put("timeoutMs", ToolParameter.optional("integer",
                "Timeout in milliseconds. Default 120000, cap 600000."));
        params.put("run_in_background", ToolParameter.optional("boolean",
                "Run in the background and return a job id immediately (collect with job_output, stop with job_kill)."));
        return params;
    }

    /** Create the tool. */
    public BashTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String command = invocation.requireString("command");
            Path workdir = resolveWorkdir(invocation, ctx);
            if (invocation.bool("run_in_background", false)) {
                String label = invocation.findString("description")
                        .filter(d -> !d.isBlank())
                        .orElse(command.length() > 60 ? command.substring(0, 60) : command);
                String id = ctx.jobs().start(label, handle -> runBackground(command, workdir, handle));
                return ToolOutcome.ok("Started background job " + id
                        + "\nCollect with job_output, stop with job_kill.");
            }
            long timeoutMs = invocation.integer("timeoutMs", (int) DEFAULT_TIMEOUT_MS);
            timeoutMs = Math.max(1, Math.min(timeoutMs, MAX_TIMEOUT_MS));
            return runForeground(command, workdir, timeoutMs);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("bash failed: " + e.getMessage());
        }
    }

    private static Path resolveWorkdir(ToolInvocation invocation, ToolContext ctx) {
        Path workspace = ctx.workspace().toAbsolutePath().normalize();
        Path workdir = invocation.findString("workdir")
                .map(w -> ctx.resolve(w, false))
                .orElse(workspace);
        if (ctx.sandbox() != SandboxMode.FULL_ACCESS && !workdir.startsWith(workspace)) {
            throw new ToolException("workdir " + workdir + " is outside the namespace cwd " + workspace);
        }
        if (!Files.isDirectory(workdir)) {
            throw new ToolException("workdir is not a directory: " + workdir);
        }
        return workdir;
    }

    private static ToolOutcome runForeground(String command, Path workdir, long timeoutMs) {
        try {
            Process process = new ProcessBuilder("bash", "-c", command)
                    .directory(workdir.toFile())
                    .start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = Thread.startVirtualThread(() -> drain(process.getInputStream(), stdout));
            Thread errReader = Thread.startVirtualThread(() -> drain(process.getErrorStream(), stderr));

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outReader.join(5_000);
            errReader.join(5_000);

            StringBuilder out = new StringBuilder(stdout);
            if (!stderr.isEmpty()) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                out.append("[stderr]\n").append(stderr);
            }
            if (!finished) {
                out.append("(timed out after ").append(timeoutMs).append(" ms; process killed)\n");
            }
            out.append("[exit code: ").append(finished ? process.exitValue() : -1).append(']');
            return ToolOutcome.ok(truncateTail(out.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolOutcome.error("bash interrupted");
        } catch (Exception e) {
            return ToolOutcome.error("bash failed: " + e.getMessage());
        }
    }

    private static void runBackground(String command, Path workdir, JobHandle handle) {
        Process process = null;
        try {
            process = new ProcessBuilder("bash", "-c", command)
                    .directory(workdir.toFile())
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            Thread reader = Thread.startVirtualThread(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) >= 0) {
                        handle.write(new String(chunk, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {
                    // stream closed because the process ended or was destroyed
                }
            });
            int exit = -1;
            while (true) {
                if (handle.cancelled()) {
                    process.destroyForcibly();
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    exit = process.exitValue();
                    break;
                }
            }
            reader.join(3_000);
            handle.write("[exit code: " + exit + "]\n");
            recordExitCode(handle, exit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            handle.write("[bash failed] " + e.getMessage() + "\n");
            recordExitCode(handle, -1);
        }
    }

    /**
     * Record the exit code on the job handle when the registry supports it, without coupling this
     * package to the job implementation.
     */
    private static void recordExitCode(JobHandle handle, int code) {
        try {
            Method setter = handle.getClass().getMethod("setExitCode", int.class);
            setter.invoke(handle, code);
        } catch (ReflectiveOperationException ignored) {
            // the registry's handle does not track exit codes; the marker line above still reports it
        }
    }

    private static void drain(InputStream in, StringBuilder target) {
        try (in) {
            target.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // stream closed because the process ended or was destroyed
        }
    }

    private static String truncateTail(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return "(truncated - showing last " + MAX_OUTPUT_CHARS + " of " + output.length() + " chars)\n"
                + output.substring(output.length() - MAX_OUTPUT_CHARS);
    }
}
