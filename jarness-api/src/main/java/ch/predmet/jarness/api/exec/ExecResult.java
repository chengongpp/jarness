package ch.predmet.jarness.api.exec;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * The result of one executor call.
 *
 * @param ok         whether the call succeeded
 * @param text       model-facing observation text
 * @param error      failure summary
 * @param exitCode   process exit code, or {@code -1} when not applicable
 * @param structured optional structured payload
 * @param duration   wall-clock time spent in the executor
 */
public record ExecResult(boolean ok, String text, String error, int exitCode, JsonNode structured,
                         Duration duration) {

    public ExecResult {
        text = text == null ? "" : text;
        error = error == null ? "" : error;
        duration = duration == null ? Duration.ZERO : duration;
    }

    /** @return a successful text result. */
    public static ExecResult ok(String text) {
        return new ExecResult(true, text, "", -1, null, Duration.ZERO);
    }

    /** @return a failed result. */
    public static ExecResult error(String message) {
        return new ExecResult(false, "", message == null ? "execution failed" : message, -1, null, Duration.ZERO);
    }

    /** @return a timed-out result. */
    public static ExecResult timeout(String message) {
        return new ExecResult(false, "", "timeout: " + (message == null ? "deadline exceeded" : message),
                -1, null, Duration.ZERO);
    }

    /** @return the text the model sees. */
    public String modelText() {
        return text.isEmpty() && !error.isEmpty() ? error : text;
    }
}
