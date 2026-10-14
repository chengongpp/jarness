package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.tool.ToolContext;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Creates the per-call tool execution context for an agent.
 *
 * <p>This is the integration point between the agent loop and the tool-context implementation,
 * which lives in another core package. The supervisor receives one factory at construction and
 * hands it to every agent it spawns; the agent loop calls it once per tool invocation so the
 * context can capture the live cancellation state of the owning turn.</p>
 */
public interface ToolContextFactory {

    /**
     * Create the execution context for one tool call.
     *
     * @param agent        the calling agent
     * @param cancelled    whether the owning turn was asked to stop
     * @param cancelReason why the turn was cancelled, or an empty string
     * @return the tool execution context
     */
    ToolContext create(Agent agent, BooleanSupplier cancelled, Supplier<String> cancelReason);
}
