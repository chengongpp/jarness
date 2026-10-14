package ch.predmet.jarness.api.session;

/** How a turn ended. */
public enum TurnState {
    /** Still executing. */
    RUNNING,
    /** The model produced a final answer without further tool calls. */
    COMPLETED,
    /** A provider, tool, or hook error ended the turn. */
    FAILED,
    /** A signal, usually {@code STOP} or {@code INTERRUPT}, ended the turn early. */
    CANCELLED,
    /** The step budget ran out before the model finished. */
    MAX_STEPS
}
