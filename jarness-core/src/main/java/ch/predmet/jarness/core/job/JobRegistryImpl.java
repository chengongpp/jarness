package ch.predmet.jarness.core.job;

import ch.predmet.jarness.api.job.JobHandle;
import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.job.JobState;
import ch.predmet.jarness.api.job.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Default {@link JobRegistry}: one virtual thread per job, in-memory output buffers, prefix id
 * lookup.
 *
 * <p>Each job owns a {@link StringBuilder} output buffer plus a single read offset, so
 * {@link #read(String)} returns the output produced since the previous read of that job (one
 * logical reader). Job ids are short and unique: {@code job-} followed by 8 hex characters.</p>
 */
public final class JobRegistryImpl implements JobRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(JobRegistryImpl.class);

    /** Guarded by itself; insertion order is the job start order. */
    private final Map<String, JobRecord> jobs = new LinkedHashMap<>();
    private volatile boolean shutdown;

    /** Create an empty registry. */
    public JobRegistryImpl() {
    }

    @Override
    public String start(String label, Consumer<JobHandle> body) {
        if (body == null) {
            throw new IllegalArgumentException("job body must not be null");
        }
        synchronized (jobs) {
            if (shutdown) {
                throw new IllegalStateException("job registry is shut down");
            }
            String id;
            do {
                id = "job-" + String.format("%08x", ThreadLocalRandom.current().nextInt());
            } while (jobs.containsKey(id));
            JobRecord record = new JobRecord(id, label == null || label.isBlank() ? id : label);
            jobs.put(id, record);
            Thread thread = Thread.ofVirtual().name("jarness-" + id).unstarted(() -> run(record, body));
            record.thread = thread;
            thread.start();
            LOG.debug("started job {} ({})", id, record.label);
            return id;
        }
    }

    private void run(JobRecord record, Consumer<JobHandle> body) {
        try {
            body.accept(record.handle);
        } catch (Throwable t) {
            record.fail(t);
        } finally {
            record.finish();
            LOG.debug("job {} finished as {}", record.id, record.state());
        }
    }

    @Override
    public Optional<JobStatus> status(String id) {
        JobRecord record = find(id);
        return record == null ? Optional.empty() : Optional.of(record.snapshot());
    }

    @Override
    public Optional<String> read(String id) {
        JobRecord record = find(id);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(record.readNew());
    }

    @Override
    public boolean kill(String id, String reason) {
        JobRecord record = find(id);
        if (record == null) {
            return false;
        }
        Thread thread;
        synchronized (record) {
            if (record.state != JobState.RUNNING) {
                return false;
            }
            record.handle.requestCancel(reason);
            thread = record.thread;
        }
        if (thread != null) {
            thread.interrupt();
        }
        return true;
    }

    @Override
    public List<JobStatus> list() {
        synchronized (jobs) {
            List<JobRecord> records = new ArrayList<>(jobs.values());
            List<JobStatus> out = new ArrayList<>(records.size());
            for (int i = records.size() - 1; i >= 0; i--) {
                out.add(records.get(i).snapshot());
            }
            return out;
        }
    }

    @Override
    public boolean awaitAll(Duration timeout) {
        long deadline = System.nanoTime() + (timeout == null ? Duration.ZERO : timeout).toNanos();
        while (true) {
            boolean allDone;
            synchronized (jobs) {
                allDone = true;
                for (JobRecord record : jobs.values()) {
                    if (record.state() == JobState.RUNNING) {
                        allDone = false;
                        break;
                    }
                }
            }
            if (allDone) {
                return true;
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(50, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Override
    public void shutdown() {
        List<JobRecord> running;
        synchronized (jobs) {
            shutdown = true;
            running = jobs.values().stream().filter(r -> r.state() == JobState.RUNNING).toList();
        }
        for (JobRecord record : running) {
            kill(record.id, "job registry shutdown");
        }
    }

    /**
     * Resolve an id or a unique prefix of one.
     *
     * @return the record, or {@code null} when unknown or ambiguous
     */
    private JobRecord find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        synchronized (jobs) {
            JobRecord exact = jobs.get(id);
            if (exact != null) {
                return exact;
            }
            JobRecord found = null;
            for (Map.Entry<String, JobRecord> entry : jobs.entrySet()) {
                if (entry.getKey().startsWith(id)) {
                    if (found != null) {
                        return null;
                    }
                    found = entry.getValue();
                }
            }
            return found;
        }
    }

    /** Mutable per-job state; the record's intrinsic lock guards everything. */
    private static final class JobRecord {

        private final String id;
        private final String label;
        private final Instant startedAt = Instant.now();
        private final JobHandleImpl handle;
        private volatile Thread thread;
        private JobState state = JobState.RUNNING;
        private Instant endedAt;
        private boolean failed;
        private int readOffset;

        JobRecord(String id, String label) {
            this.id = id;
            this.label = label;
            this.handle = new JobHandleImpl(id);
        }

        synchronized JobState state() {
            return state;
        }

        synchronized JobStatus snapshot() {
            return new JobStatus(id, label, state, startedAt, endedAt, handle.exitCode(), handle.outputBytes());
        }

        synchronized String readNew() {
            String output = handle.output();
            int from = Math.min(readOffset, output.length());
            readOffset = output.length();
            return output.substring(from);
        }

        void fail(Throwable t) {
            synchronized (this) {
                failed = true;
            }
            handle.write("\n[job failed] " + (t.getMessage() == null ? t.toString() : t.getMessage()) + "\n");
        }

        synchronized void finish() {
            if (state != JobState.RUNNING) {
                return;
            }
            state = handle.cancelled() ? JobState.KILLED : (failed ? JobState.FAILED : JobState.DONE);
            endedAt = Instant.now();
        }
    }

    /**
     * {@link JobHandle} implementation handed to job bodies.
     *
     * <p>Beyond the interface it exposes {@link #setExitCode(int)} so process-backed jobs (e.g. the
     * bash tool) can record a process-style exit code on the status snapshot.</p>
     */
    public static final class JobHandleImpl implements JobHandle {

        private final String id;
        private final StringBuilder output = new StringBuilder();
        private long outputBytes;
        private boolean cancelled;
        private String cancelReason = "";
        private int exitCode = -1;

        JobHandleImpl(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public synchronized void write(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            output.append(text);
            outputBytes += text.getBytes(StandardCharsets.UTF_8).length;
        }

        @Override
        public synchronized boolean cancelled() {
            return cancelled;
        }

        @Override
        public synchronized String cancelReason() {
            return cancelReason;
        }

        synchronized void requestCancel(String reason) {
            cancelled = true;
            cancelReason = reason == null ? "" : reason;
        }

        /** @param code process-style exit code recorded on the status snapshot */
        public synchronized void setExitCode(int code) {
            this.exitCode = code;
        }

        synchronized int exitCode() {
            return exitCode;
        }

        synchronized long outputBytes() {
            return outputBytes;
        }

        synchronized String output() {
            return output.toString();
        }
    }
}
