package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.session.Step;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable in-memory {@link Turn} implementation.
 *
 * <p>A turn starts {@link TurnState#RUNNING}; {@link #finish(TurnState, String, String)} moves it
 * to a terminal state and stamps {@code endedAt}. Steps accumulate via {@link #addStep(Step)} and
 * the token counters are derived from them. Persistence is the session store's job; this class
 * only holds state.</p>
 */
public class TurnImpl implements Turn {

    private final String id;
    private final String sessionId;
    private final int index;
    private final String input;
    private final Instant startedAt;
    private final List<Step> steps;

    private TurnState state;
    private String output;
    private String error;
    private Instant endedAt;

    /**
     * Start a new running turn.
     *
     * @param id        turn id, or {@code null}/blank to generate one
     * @param sessionId owning session id
     * @param index     0-based index within the session
     * @param input     the user input that opened the turn
     */
    public TurnImpl(String id, String sessionId, int index, String input) {
        this(id, sessionId, index, TurnState.RUNNING, input, "", null, Instant.now(), null, List.of());
    }

    /**
     * Restore a persisted turn in full.
     *
     * @param id        turn id
     * @param sessionId owning session id
     * @param index     0-based index within the session
     * @param state     persisted state
     * @param input     the user input
     * @param output    final assistant text, or {@code null}
     * @param error     terminal error, or {@code null}
     * @param startedAt start time
     * @param endedAt   end time, or {@code null} while running
     * @param steps     executed steps
     */
    public TurnImpl(String id, String sessionId, int index, TurnState state, String input, String output,
                    String error, Instant startedAt, Instant endedAt, List<Step> steps) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.index = index;
        this.state = state == null ? TurnState.RUNNING : state;
        this.input = input == null ? "" : input;
        this.output = output == null ? "" : output;
        this.error = error;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.endedAt = endedAt;
        this.steps = new ArrayList<>(steps == null ? List.of() : steps);
    }

    /**
     * Restore a persisted turn.
     *
     * @return the restored turn
     */
    public static TurnImpl restore(String id, String sessionId, int index, TurnState state, String input,
                                   String output, String error, Instant startedAt, Instant endedAt,
                                   List<Step> steps) {
        return new TurnImpl(id, sessionId, index, state, input, output, error, startedAt, endedAt, steps);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public int index() {
        return index;
    }

    @Override
    public TurnState state() {
        return state;
    }

    @Override
    public String input() {
        return input;
    }

    @Override
    public String output() {
        return output;
    }

    @Override
    public String error() {
        return error;
    }

    @Override
    public Instant startedAt() {
        return startedAt;
    }

    @Override
    public Instant endedAt() {
        return endedAt;
    }

    @Override
    public List<Step> steps() {
        return List.copyOf(steps);
    }

    @Override
    public int promptTokens() {
        int sum = 0;
        for (Step step : steps) {
            sum += step.promptTokens();
        }
        return sum;
    }

    @Override
    public int completionTokens() {
        int sum = 0;
        for (Step step : steps) {
            sum += step.completionTokens();
        }
        return sum;
    }

    @Override
    public void addStep(Step step) {
        steps.add(Objects.requireNonNull(step, "step"));
    }

    @Override
    public void finish(TurnState state, String output, String error) {
        this.state = Objects.requireNonNull(state, "state");
        this.output = output == null ? "" : output;
        this.error = error;
        this.endedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "TurnImpl[id=" + id + ", session=" + sessionId + ", index=" + index + ", state=" + state + "]";
    }
}
