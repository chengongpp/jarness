package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.exec.ExecutionException;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolForm;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolMode;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A tool whose handler lives in an executor.
 *
 * <p>The wrapper keeps the model-facing {@link ToolSpec} and capability declaration of the built-in
 * tool, but routes the call through the runtime's {@link ch.predmet.jarness.api.exec.ExecutionRegistry}.
 * Missing executor means a failed observation, never a silent in-daemon fallback.</p>
 */
public final class RelayTool implements Tool {

    private final Tool delegate;
    private final String relayName;

    private RelayTool(Tool delegate, String relayName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.relayName = Objects.requireNonNull(relayName, "relayName");
    }

    /**
     * Wrap a tool for relay.
     *
     * @param delegate  the built-in tool whose spec and capability declaration are reused
     * @param relayName executor tool name, e.g. {@code fs.read}
     * @return the relay tool
     */
    public static RelayTool of(Tool delegate, String relayName) {
        return new RelayTool(delegate, relayName);
    }

    @Override
    public ToolSpec spec() {
        return delegate.spec();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        return dispatch(this, invocation, ctx);
    }

    /**
     * Dispatch any declarative {@link ToolForm#RELAY} tool. Mods do not need to link this core
     * wrapper: the agent loop calls this path from the API-level {@code form()/relayName()} contract.
     *
     * @param declaration tool declaration
     * @param invocation  parsed invocation
     * @param ctx         calling context
     * @return the executor result as a tool observation
     */
    public static ToolOutcome dispatch(Tool declaration, ToolInvocation invocation, ToolContext ctx) {
        Instant deadline = ctx.budget().deadline();
        String callId = ctx.agent().id() + ":" + (invocation.id() == null || invocation.id().isBlank()
                ? UUID.randomUUID().toString() : invocation.id());
        ExecRequest request = new ExecRequest(
                callId,
                declaration.relayName(),
                invocation.arguments(),
                deadline,
                declaration.mode(),
                callId,
                ctx.workspace().toString(),
                ctx.sandbox().id(),
                ctx.capabilities(),
                ctx.agent().namespaceId());
        if (invocation.bool("run_in_background", false)) {
            String label = invocation.findString("description").filter(text -> !text.isBlank())
                    .orElse(declaration.relayName());
            String jobId = ctx.jobs().start(label, handle -> {
                try {
                    ExecResult result = ctx.execution().invoke(request);
                    handle.write(result.modelText());
                } catch (ExecutionException e) {
                    handle.write("execution_failed: " + e.getMessage());
                }
            });
            return ToolOutcome.ok("Started background job " + jobId
                    + "\nCollect with job_output, stop with job_kill.");
        }
        try {
            ExecResult result = ctx.execution().invoke(request);
            return new ToolOutcome(result.ok(), result.text(), result.structured(), result.error());
        } catch (ExecutionException e) {
            return ToolOutcome.error("execution_failed: " + e.getMessage());
        }
    }

    @Override
    public Set<CapKind> requiredCaps() {
        return delegate.requiredCaps();
    }

    @Override
    public ToolForm form() {
        return ToolForm.RELAY;
    }

    @Override
    public String relayName() {
        return relayName;
    }

    @Override
    public ToolMode mode() {
        return delegate.mode();
    }

    /** @return the wrapped in-daemon tool. */
    public Tool delegate() {
        return delegate;
    }
}
