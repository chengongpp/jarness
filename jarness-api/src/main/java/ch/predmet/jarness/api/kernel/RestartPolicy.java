package ch.predmet.jarness.api.kernel;

/** What happens to an agent that exits unexpectedly. */
public enum RestartPolicy {

    /** Never restart. The agent stays stopped. */
    NO,
    /** Restart only when the exit was a failure. */
    ON_FAILURE,
    /** Restart regardless of how the agent exited. */
    ALWAYS
}
