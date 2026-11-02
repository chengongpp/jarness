package ch.predmet.jarness.api.provider;

/**
 * A provider failure that should surface to the agent as an error, not crash the runtime.
 */
public class ProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final Boolean retryableOverride;

    /**
     * @param message human readable failure
     */
    public ProviderException(String message) {
        this(message, -1, null);
    }

    /**
     * @param message human readable failure
     * @param cause   underlying failure
     */
    public ProviderException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    /**
     * @param message    human readable failure
     * @param statusCode HTTP status, or {@code -1}
     * @param cause      underlying failure
     */
    public ProviderException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, cause, null);
    }

    private ProviderException(String message, int statusCode, Throwable cause, Boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryableOverride = retryable;
    }

    /** @return a retryable transport failure (including connect/read timeouts). */
    public static ProviderException transport(String message, Throwable cause) {
        return new ProviderException(message, -1, cause, true);
    }

    /** @return a retryable malformed-response failure. */
    public static ProviderException parse(String message, Throwable cause) {
        return new ProviderException(message, -1, cause, true);
    }

    /** @return the HTTP status code, or {@code -1} when the failure was not an HTTP error. */
    public int statusCode() {
        return statusCode;
    }

    /** @return whether the failure is worth retrying without changing the request. */
    public boolean retryable() {
        return retryableOverride != null ? retryableOverride
                : statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500;
    }
}
