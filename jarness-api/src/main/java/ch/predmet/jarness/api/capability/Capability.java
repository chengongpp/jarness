package ch.predmet.jarness.api.capability;

import java.util.Objects;
import java.util.UUID;

/**
 * One granted capability: a kind, a resource scope, and the limits that came with it.
 *
 * <p>The {@code capId} is what revocation and the derivation graph speak about; a freshly spawned
 * agent receives new ids for the capabilities it inherits, with the derivation DAG remembering
 * which parent grant each came from.</p>
 *
 * @param capId    stable grant id, unique within a runtime
 * @param kind     the kind of authority
 * @param resource the resource scope, {@code *} for any
 * @param limits   the limits on this grant
 */
public record Capability(String capId, CapKind kind, String resource, Constraints limits) {

    public Capability {
        Objects.requireNonNull(kind, "kind");
        capId = capId == null || capId.isBlank() ? "cap-" + UUID.randomUUID() : capId;
        resource = resource == null || resource.isBlank() ? "*" : resource.trim();
        limits = limits == null ? Constraints.unbounded() : limits;
    }

    /** @return a grant of {@code kind} over any resource, unbounded. */
    public static Capability of(CapKind kind) {
        return new Capability(null, kind, "*", Constraints.unbounded());
    }

    /** @return a grant of {@code kind} over {@code resource}, unbounded. */
    public static Capability of(CapKind kind, String resource) {
        return new Capability(null, kind, resource, Constraints.unbounded());
    }

    /** @return this grant as the request it satisfies. */
    public CapabilityRequest asRequest() {
        return new CapabilityRequest(kind, resource, limits);
    }

    /** @return a copy with a different grant id. */
    public Capability withCapId(String newCapId) {
        return new Capability(newCapId, kind, resource, limits);
    }

    /**
     * @param requested what a tool or child agent asks for
     * @return whether this grant satisfies the request
     */
    public boolean covers(CapabilityRequest requested) {
        return requested != null
                && kind == requested.kind()
                && coversResource(resource, requested.resource())
                && limits.covers(requested.limits());
    }

    /**
     * Whether a grant over {@code grantResource} reaches {@code requestedResource}.
     *
     * <p>Scope syntax is deliberately small and portable: {@code *} matches everything, a
     * {@code /prefix/*} path matches below that prefix, and a {@code *.example.com} domain matches
     * subdomains. Anything else is an exact match. The execution side may interpret scopes more
     * richly; core only needs the containment relation for attenuation.</p>
     *
     * @param grantResource     the scope of the grant
     * @param requestedResource the scope being requested
     * @return whether the grant reaches the requested scope
     */
    public static boolean coversResource(String grantResource, String requestedResource) {
        if (grantResource == null || grantResource.isBlank() || grantResource.equals("*")) {
            return true;
        }
        if (requestedResource == null || requestedResource.isBlank()) {
            return false;
        }
        if (grantResource.equals(requestedResource)) {
            return true;
        }
        if (grantResource.endsWith("/*")) {
            String root = grantResource.substring(0, grantResource.length() - 2);
            return requestedResource.equals(root) || requestedResource.startsWith(root + "/");
        }
        if (grantResource.startsWith("*.")) {
            String domain = grantResource.substring(1);
            return requestedResource.endsWith(domain) || requestedResource.equals(grantResource.substring(2));
        }
        return false;
    }

    /**
     * Whether two scopes overlap at all, i.e. one reaches the other.
     *
     * @param a one scope
     * @param b another scope
     * @return whether the scopes intersect
     */
    public static boolean overlaps(String a, String b) {
        return coversResource(a, b) || coversResource(b, a);
    }
}
