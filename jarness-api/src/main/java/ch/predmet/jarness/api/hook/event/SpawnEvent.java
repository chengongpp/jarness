package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.kernel.AgentManifest;
import ch.predmet.jarness.api.kernel.SpawnDeniedException;

/**
 * Payload of {@link ch.predmet.jarness.api.hook.HookPoint#SPAWN_DENIED}.
 *
 * <p>A refused spawn is an event like any other: "too deep", "too many children", "over budget",
 * "cross namespace", and "missing template" are all observable, which is what makes a bounded
 * supervisor auditable rather than merely strict.</p>
 *
 * @param parentPid   the parent that asked
 * @param namespaceId the targeted namespace
 * @param manifest    the manifest that was refused
 * @param reason      the refusal category
 * @param message     human-readable explanation
 */
public record SpawnEvent(long parentPid, String namespaceId, AgentManifest manifest,
                         SpawnDeniedException.Reason reason, String message) {
}
