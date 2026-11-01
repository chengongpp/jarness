package ch.predmet.jarness.api.kernel;

/** How an agent exited. */
public enum ExitKind {

    /** The agent finished its work and stopped normally. */
    NORMAL,
    /** The agent stopped because of an unrecoverable error. */
    FAILED,
    /** The agent was killed, directly or as part of a subtree. */
    KILLED,
    /** The agent was cancelled by a signal. */
    CANCELLED
}
