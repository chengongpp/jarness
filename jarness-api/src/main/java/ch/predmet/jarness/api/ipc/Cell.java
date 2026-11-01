package ch.predmet.jarness.api.ipc;

import java.time.Instant;

/**
 * One versioned cell of a blackboard.
 *
 * @param name      cell name
 * @param version   monotonically increasing version, starting at 1; 0 means absent
 * @param value     the value, or {@code null} for a deleted cell
 * @param updatedAt last write time
 * @param ownerPid  pid that produced this version
 */
public record Cell(String name, long version, Object value, Instant updatedAt, long ownerPid) {
}
