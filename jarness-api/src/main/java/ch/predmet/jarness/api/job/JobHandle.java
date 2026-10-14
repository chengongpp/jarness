package ch.predmet.jarness.api.job;

/**
 * Handle a background job body uses to publish output and observe cancellation.
 */
public interface JobHandle {

    /** @return the job id. */
    String id();

    /**
     * Publish output for later collection.
     *
     * @param text text to append
     */
    void write(String text);

    /** @return whether a kill was requested. */
    boolean cancelled();

    /** @return the reason given to {@link JobRegistry#kill(String, String)}, or an empty string. */
    String cancelReason();
}
