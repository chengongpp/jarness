package ch.predmet.jarness.api.session;

import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.kernel.Budget;

import java.time.Instant;
import java.util.Map;

/**
 * The durable metadata of one agent image.
 *
 * <p>An image is the authoritative context of an agent: the conversation, the counters, the
 * remaining budget, the capabilities the agent holds, and the dependency manifest that must still
 * hold when the image is loaded again. This record is the metadata half; the message and turn
 * history lives in the store and is checkpointed separately.</p>
 *
 * @param agentId       agent id string, e.g. {@code agent-7}
 * @param sessionId     owning session id
 * @param namespace     namespace id the agent was bound to
 * @param schemaVersion image schema version
 * @param checkpointTurn last turn covered by a checkpoint, or {@code -1}
 * @param deps          dependency manifest: template id/version, loop, mods, tools
 * @param budget        remaining budget at the last commit
 * @param capabilities  capabilities held at the last commit
 * @param state         lifecycle state name at the last commit
 * @param updatedAt     when the image was last written
 */
public record ImageMetadata(String agentId, String sessionId, String namespace, int schemaVersion,
                            int checkpointTurn, Map<String, Object> deps, Budget budget,
                            CapabilitySet capabilities, String state, Instant updatedAt) {

    /** Current image schema version. */
    public static final int SCHEMA_VERSION = 1;

    public ImageMetadata {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        deps = deps == null ? Map.of() : Map.copyOf(deps);
        budget = budget == null ? Budget.unlimited() : budget;
        capabilities = capabilities == null ? CapabilitySet.empty() : capabilities;
        state = state == null ? "IDLE" : state;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    /** @return a minimal image record for a session that has no image yet. */
    public static ImageMetadata of(String agentId, String sessionId, String namespace) {
        return new ImageMetadata(agentId, sessionId, namespace, SCHEMA_VERSION, -1, Map.of(),
                Budget.unlimited(), CapabilitySet.empty(), "NEW", Instant.now());
    }
}
