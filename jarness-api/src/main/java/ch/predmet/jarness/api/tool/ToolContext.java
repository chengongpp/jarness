package ch.predmet.jarness.api.tool;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.exec.ExecutionRegistry;
import ch.predmet.jarness.api.config.SandboxMode;
import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.session.Session;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Execution environment of one tool call.
 */
public interface ToolContext {

    /** @return the agent that requested the call. */
    Agent agent();

    /** @return the agent's session. */
    Session session();

    /** @return the running runtime. */
    JarnessRuntime runtime();

    /** @return the agent's working directory; relative paths resolve against it. */
    Path workspace();

    /** @return the capabilities the calling agent currently holds. */
    CapabilitySet capabilities();

    /** @return the calling agent's remaining budget. */
    Budget budget();

    /** @return the runtime's execution routing table. */
    default ExecutionRegistry execution() {
        return runtime().execution();
    }

    /** @return the sandbox mode this call must respect. */
    SandboxMode sandbox();

    /** @return the runtime configuration document. */
    ConfigView config();

    /** @return the background job registry. */
    JobRegistry jobs();

    /** @return a logger tagged with the agent and tool. */
    Logger log();

    /** @return whether the owning turn was asked to stop. */
    boolean isCancelled();

    /** @return why the turn was cancelled, or an empty string. */
    default String cancelReason() {
        return "";
    }

    /**
     * Resolve a model-supplied path against the workspace.
     *
     * @param path absolute or workspace-relative path
     * @return the normalized absolute path
     */
    default Path resolve(String path) {
        Path candidate = Path.of(path);
        Path resolved = candidate.isAbsolute() ? candidate : workspace().resolve(candidate);
        return resolved.normalize().toAbsolutePath();
    }

    /**
     * Resolve a path and enforce the sandbox when the caller intends to write.
     *
     * @param path   absolute or workspace-relative path
     * @param intent {@code true} when the caller will modify the target
     * @return the normalized absolute path
     * @throws ToolException when the sandbox forbids the write
     */
    default Path resolve(String path, boolean intent) {
        Path resolved = resolve(path);
        if (!intent) {
            return resolved;
        }
        switch (sandbox()) {
            case FULL_ACCESS -> {
                return resolved;
            }
            case READ_ONLY -> throw new ToolException(
                    "sandbox mode is read-only: writing " + resolved + " is not allowed");
            case WORKSPACE_WRITE -> {
                Path root = workspace().toAbsolutePath().normalize();
                if (!resolved.startsWith(root)) {
                    throw new ToolException("sandbox mode is workspace-write: " + resolved
                            + " is outside the agent workspace " + root);
                }
                return resolved;
            }
            default -> throw new ToolException("unhandled sandbox mode " + sandbox());
        }
    }
}
