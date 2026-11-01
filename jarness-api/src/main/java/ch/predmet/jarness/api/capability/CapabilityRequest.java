package ch.predmet.jarness.api.capability;

import java.util.Objects;

/**
 * A capability an agent or tool asks for, before it is matched against the grants it holds.
 *
 * @param kind     the kind of authority requested
 * @param resource the resource scope; {@code *} means any resource of this kind
 * @param limits   the narrowest acceptable limits
 */
public record CapabilityRequest(CapKind kind, String resource, Constraints limits) {

    public CapabilityRequest {
        Objects.requireNonNull(kind, "kind");
        resource = resource == null || resource.isBlank() ? "*" : resource.trim();
        limits = limits == null ? Constraints.unbounded() : limits;
    }

    /** @return a request for any resource of {@code kind}. */
    public static CapabilityRequest of(CapKind kind) {
        return new CapabilityRequest(kind, "*", Constraints.unbounded());
    }

    /** @return a request for {@code resource} of {@code kind}. */
    public static CapabilityRequest of(CapKind kind, String resource) {
        return new CapabilityRequest(kind, resource, Constraints.unbounded());
    }
}
