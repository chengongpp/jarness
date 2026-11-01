package ch.predmet.jarness.api.session;

import java.util.List;

/**
 * Thrown when an image cannot be resumed because its dependency manifest no longer matches.
 *
 * <p>The design's rule is "reject the load, unless forced; a forced load degrades the tool set and
 * injects a system-prompt notice". This exception is the refusal half.</p>
 */
public final class ResumeDeniedException extends RuntimeException {

    private final List<String> problems;

    /**
     * @param sessionId the image's session id
     * @param problems  what no longer matches
     */
    public ResumeDeniedException(String sessionId, List<String> problems) {
        super("cannot resume image " + sessionId + ": " + String.join("; ", problems)
                + " (use --force-resume to load it with degraded tools)");
        this.problems = List.copyOf(problems);
    }

    /** @return the individual mismatch descriptions. */
    public List<String> problems() {
        return problems;
    }
}
