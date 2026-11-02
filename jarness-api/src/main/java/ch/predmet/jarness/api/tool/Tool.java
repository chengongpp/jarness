package ch.predmet.jarness.api.tool;

import ch.predmet.jarness.api.capability.CapKind;

import java.util.Set;

/**
 * A tool the model can call.
 *
 * <p>Implementations should be thread-safe: read-only tools may be invoked concurrently by the loop,
 * and a running runtime may serve several agents at once.</p>
 *
 * <p>A tool declares the capability kinds it needs. The dispatcher refuses a call when the agent's
 * capability set does not contain every required kind, before the tool body runs. Resource-level
 * scope is enforced by the execution side, not by this declaration.</p>
 */
public interface Tool {

    /** @return the model-facing contract of this tool. */
    ToolSpec spec();

    /**
     * Execute one call.
     *
     * @param invocation parsed call arguments
     * @param ctx        the calling agent's context
     * @return the observation to send back to the model
     * @throws Exception when the tool fails in a way the loop should turn into a failed observation
     */
    ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception;

    /**
     * @return the capability kinds an agent must hold to call this tool; empty for capability-free
     *         tools such as a pure calculation
     */
    default Set<CapKind> requiredCaps() {
        return Set.of();
    }

    /** @return whether the handler runs in the daemon or is relayed to an executor. */
    default ToolForm form() {
        return ToolForm.LOCAL;
    }

    /**
     * @return the executor tool name; only meaningful for {@link ToolForm#RELAY}
     *
     * <p>The agent dispatcher routes every RELAY declaration through the execution registry. A mod
     * only needs to override {@link #form()} and this method; it does not need a core wrapper.</p>
     */
    default String relayName() {
        return name();
    }

    /** @return the call mode; ASYNC completions are delivered through the agent mailbox. */
    default ToolMode mode() {
        return ToolMode.SYNC;
    }

    /** @return the tool's name, a shorthand for {@code spec().name()}. */
    default String name() {
        return spec().name();
    }
}
