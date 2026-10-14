package ch.predmet.jarness.core.tool;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.SandboxMode;
import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Per-call {@link ToolContext} implementation: a thin, immutable view over the calling agent, the
 * runtime, and the owning turn's cancellation state.
 *
 * <p>The sandbox mode is read from the raw configuration key {@code sandbox.mode} and defaults to
 * {@link SandboxMode#WORKSPACE_WRITE}.</p>
 */
public final class ToolContextImpl implements ToolContext {

    private final Agent agent;
    private final JarnessRuntime runtime;
    private final BooleanSupplier cancelled;
    private final Supplier<String> cancelReason;
    private final Logger log;

    /**
     * @param agent        the calling agent
     * @param runtime      the running runtime
     * @param cancelled    whether the owning turn was asked to stop
     * @param cancelReason why the turn was cancelled
     */
    public ToolContextImpl(ch.predmet.jarness.api.agent.Agent agent,
                           ch.predmet.jarness.api.JarnessRuntime runtime,
                           java.util.function.BooleanSupplier cancelled,
                           java.util.function.Supplier<String> cancelReason) {
        this.agent = agent;
        this.runtime = runtime;
        this.cancelled = cancelled;
        this.cancelReason = cancelReason;
        this.log = LoggerFactory.getLogger("jarness.tool." + agent.id());
    }

    @Override
    public Agent agent() {
        return agent;
    }

    @Override
    public Session session() {
        return agent.session();
    }

    @Override
    public JarnessRuntime runtime() {
        return runtime;
    }

    @Override
    public Path workspace() {
        return agent.workspace();
    }

    @Override
    public SandboxMode sandbox() {
        return SandboxMode.parse(runtime.config().raw().string("sandbox.mode", "workspace-write"),
                SandboxMode.WORKSPACE_WRITE);
    }

    @Override
    public ConfigView config() {
        return runtime.config().raw();
    }

    @Override
    public JobRegistry jobs() {
        return runtime.jobs();
    }

    @Override
    public Logger log() {
        return log;
    }

    @Override
    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    @Override
    public String cancelReason() {
        String reason = cancelReason.get();
        return reason == null ? "" : reason;
    }
}
