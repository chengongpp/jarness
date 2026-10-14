package ch.predmet.jarness.api.tool;

/**
 * A tool failure the model is meant to read and recover from.
 *
 * <p>Thrown for bad arguments and refused operations; the loop converts it into a failed
 * observation instead of failing the turn.</p>
 */
public class ToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message model-facing explanation
     */
    public ToolException(String message) {
        super(message);
    }

    /**
     * @param message model-facing explanation
     * @param cause   underlying failure
     */
    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
