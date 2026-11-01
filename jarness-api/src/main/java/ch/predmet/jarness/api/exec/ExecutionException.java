package ch.predmet.jarness.api.exec;

/** Raised when no execution provider can run a call, or the provider fails before a result exists. */
public final class ExecutionException extends RuntimeException {

    /** Why the call could not be routed or completed. */
    public enum Reason {
        /** No provider advertises the tool or the required capabilities. */
        NO_PROVIDER,
        /** The provider exists but its executor is dead. */
        PROVIDER_DEAD,
        /** The provider rejected the call (auth, protocol, overload). */
        REJECTED,
        /** The request is not supported by this provider. */
        UNSUPPORTED
    }

    private final Reason reason;

    /**
     * @param reason  failure category
     * @param message explanation
     */
    public ExecutionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** @return the failure category. */
    public Reason reason() {
        return reason;
    }
}
