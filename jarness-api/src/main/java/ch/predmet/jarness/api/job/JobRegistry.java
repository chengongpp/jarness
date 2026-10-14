package ch.predmet.jarness.api.job;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Registry of long-running work started by tools, the analogue of the shell tool's background jobs.
 *
 * <p>Jobs run on their own threads and are owned by the runtime, not by the tool call that started
 * them, so a model can start a server, keep working, and collect its output later.</p>
 */
public interface JobRegistry {

    /**
     * Start a job.
     *
     * @param label short description shown in listings
     * @param body  the work; the handle publishes output and observes cancellation
     * @return the job id
     */
    String start(String label, Consumer<JobHandle> body);

    /**
     * Read a job's status.
     *
     * @param id job id, or a unique prefix of it
     * @return the status, or empty when unknown or ambiguous
     */
    Optional<JobStatus> status(String id);

    /**
     * Read output produced since the previous read of this job.
     *
     * @param id job id or unique prefix
     * @return the new output, or empty when the job is unknown
     */
    Optional<String> read(String id);

    /**
     * Request cancellation.
     *
     * @param id     job id or unique prefix
     * @param reason short reason recorded on the job
     * @return whether a running job accepted the request
     */
    boolean kill(String id, String reason);

    /** @return every job, newest first. */
    List<JobStatus> list();

    /**
     * Wait for all jobs to finish.
     *
     * @param timeout maximum wait
     * @return whether every job finished within the timeout
     */
    boolean awaitAll(Duration timeout);

    /** Cancel everything and stop the registry's threads. */
    void shutdown();
}
