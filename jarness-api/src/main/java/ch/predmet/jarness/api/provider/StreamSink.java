package ch.predmet.jarness.api.provider;

/**
 * Streaming callbacks.
 *
 * <p>Every method has a no-op default so that adapters that cannot stream, and callers that do not
 * care, stay simple. A sink may also observe cancellation, which lets the loop stop a long
 * generation when it receives a {@code STOP} or {@code INTERRUPT} signal.</p>
 */
public interface StreamSink {

    /** A sink that discards every event. */
    StreamSink DISCARD = new StreamSink() {
    };

    /**
     * The provider accepted the request.
     *
     * @param responseId provider response id
     */
    default void onStart(String responseId) {
    }

    /**
     * Assistant text delta.
     *
     * @param delta incremental text
     */
    default void onText(String delta) {
    }

    /**
     * Reasoning delta, when the provider exposes it.
     *
     * @param delta incremental reasoning text
     */
    default void onReasoning(String delta) {
    }

    /**
     * A tool call began.
     *
     * @param index provider-side call index
     * @param callId provider call id, when already known
     * @param name   tool name, when already known
     */
    default void onToolCallStart(int index, String callId, String name) {
    }

    /**
     * Incremental JSON arguments for a tool call.
     *
     * @param index provider-side call index
     * @param delta arguments fragment
     */
    default void onToolCallArguments(int index, String delta) {
    }

    /**
     * Token usage reported by the provider.
     *
     * @param usage the usage
     */
    default void onUsage(Usage usage) {
    }

    /**
     * The provider finished.
     *
     * @param finishReason provider finish reason
     */
    default void onFinish(String finishReason) {
    }

    /** @return whether the caller wants the stream abandoned. */
    default boolean cancelled() {
        return false;
    }
}
