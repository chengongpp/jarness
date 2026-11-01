package ch.predmet.jarness.api.session;

import java.time.Instant;
import java.util.Map;

/**
 * One committed event in the observation stream of a session.
 *
 * <p>Events are a by-product of the image: they are written in the same transaction as the turn
 * that produced them, so an observer can never see an event for a turn that was rolled back.</p>
 *
 * @param id      event id
 * @param sessionId owning session
 * @param turnSeq turn index the event belongs to
 * @param type    event type, e.g. {@code turn.committed}, {@code message}, {@code tool}
 * @param payload JSON-shaped payload
 * @param at      when the event happened
 */
public record EventRecord(String id, String sessionId, int turnSeq, String type,
                          Map<String, Object> payload, Instant at) {

    public EventRecord {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        at = at == null ? Instant.now() : at;
    }
}
