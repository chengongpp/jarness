package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentSignal;

/**
 * Payload for the agent lifecycle points.
 *
 * @param agent  the agent
 * @param reason human readable reason, e.g. {@code "created"} or a stop reason
 * @param signal the signal involved, or {@code null}
 */
public record AgentEvent(Agent agent, String reason, AgentSignal signal) {

    /** @return the event without a signal. */
    public static AgentEvent of(Agent agent, String reason) {
        return new AgentEvent(agent, reason, null);
    }
}
