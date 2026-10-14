package ch.predmet.jarness.api.session;

import java.time.Instant;

/**
 * Lightweight session description for listings, without materializing history.
 *
 * @param id           session id
 * @param agentId      owning agent id
 * @param title        session title
 * @param templateId   template id
 * @param createdAt    creation time
 * @param updatedAt    last activity
 * @param messageCount number of stored messages
 * @param turnCount    number of stored turns
 */
public record SessionSummary(String id, String agentId, String title, String templateId,
                             Instant createdAt, Instant updatedAt, int messageCount, int turnCount) {
}
