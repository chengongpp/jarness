package ch.predmet.jarness.api.loop;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.session.Turn;

/**
 * A structural hook into the agent loop, separate from the observable {@code HookBus}.
 *
 * <p>Loop hooks are for policies that need turn/step boundaries (budget accounting, tracing,
 * custom compaction); they run in order and a throwing hook is isolated by the runtime.</p>
 */
public interface LoopHook {

    /** @return order among hooks; lower runs first. */
    default int order() {
        return 0;
    }

    /** @return stable hook name. */
    default String name() {
        return getClass().getName();
    }

    /**
     * Called after a turn starts and before its first step.
     *
     * @param agent the agent
     * @param turn  the running turn
     */
    default void beforeTurn(Agent agent, Turn turn) {
    }

    /**
     * Called before a provider request.
     *
     * @param agent the agent
     * @param turn  the turn
     * @param step  step index
     */
    default void beforeStep(Agent agent, Turn turn, int step) {
    }

    /**
     * Called after a step has been recorded.
     *
     * @param agent the agent
     * @param turn  the turn
     * @param step  step index
     */
    default void afterStep(Agent agent, Turn turn, int step) {
    }

    /**
     * Called after a turn finishes, before its commit.
     *
     * @param agent the agent
     * @param turn  the turn
     */
    default void afterTurn(Agent agent, Turn turn) {
    }
}
