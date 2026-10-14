package ch.predmet.jarness.api.job;

import java.time.Instant;

/**
 * Snapshot of one background job.
 *
 * @param id        job id handed back to the model
 * @param label     short description, usually the command
 * @param state     lifecycle state
 * @param startedAt start time
 * @param endedAt   end time, or {@code null} while running
 * @param exitCode  process-style exit code; {@code -1} when not applicable
 * @param outputBytes bytes produced so far
 */
public record JobStatus(String id, String label, JobState state, Instant startedAt, Instant endedAt,
                        int exitCode, long outputBytes) {

    /** @return whether the job is still running. */
    public boolean running() {
        return state == JobState.RUNNING;
    }
}
