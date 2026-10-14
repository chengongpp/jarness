package ch.predmet.jarness.api.tool;

/**
 * A tool the model can call.
 *
 * <p>Implementations should be thread-safe: read-only tools may be invoked concurrently by the loop,
 * and a running runtime may serve several agents at once.</p>
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

    /** @return the tool's name, a shorthand for {@code spec().name()}. */
    default String name() {
        return spec().name();
    }
}
