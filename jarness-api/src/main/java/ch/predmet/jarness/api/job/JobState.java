package ch.predmet.jarness.api.job;

import java.time.Instant;

/** Lifecycle state of one background job. */
public enum JobState {
    RUNNING,
    DONE,
    FAILED,
    KILLED
}
