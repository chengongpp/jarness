package ch.predmet.jarness.api.kernel;

/**
 * The outcome of an agent's life, as seen by the supervisor.
 *
 * @param kind   how the agent exited
 * @param reason short human-readable reason
 */
public record ExitStatus(ExitKind kind, String reason) {

    public ExitStatus {
        kind = kind == null ? ExitKind.NORMAL : kind;
        reason = reason == null ? "" : reason;
    }

    /** @return {@code true} when the exit was a failure. */
    public boolean failed() {
        return kind == ExitKind.FAILED;
    }
}
