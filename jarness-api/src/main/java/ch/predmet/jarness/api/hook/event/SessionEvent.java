package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.session.Session;

/**
 * Payload for the session lifecycle points.
 *
 * @param agent   the owning agent, or {@code null} when a session is touched outside an agent
 * @param session the session
 */
public record SessionEvent(Agent agent, Session session) {
}
