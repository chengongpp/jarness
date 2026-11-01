package ch.predmet.jarness.api.capability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable set of granted capabilities.
 *
 * <p>Inheritance is intersection: {@code effective = requested ∩ parent ∩ ns.defaults}. The
 * intersection takes the narrower resource scope and the narrower limits from each matching pair,
 * so a child never gains authority its parent did not hold and never gains a wider scope than the
 * namespace allows.</p>
 */
public final class CapabilitySet {

    private static final CapabilitySet EMPTY = new CapabilitySet(List.of());

    private final List<Capability> capabilities;

    private CapabilitySet(List<Capability> capabilities) {
        this.capabilities = List.copyOf(capabilities);
    }

    /** @return the empty set. */
    public static CapabilitySet empty() {
        return EMPTY;
    }

    /** @return a set holding exactly the given grants. */
    public static CapabilitySet of(Capability... capabilities) {
        return capabilities == null || capabilities.length == 0
                ? EMPTY
                : new CapabilitySet(List.of(capabilities));
    }

    /** @return a set holding exactly the given grants. */
    public static CapabilitySet of(Collection<Capability> capabilities) {
        return capabilities == null || capabilities.isEmpty()
                ? EMPTY
                : new CapabilitySet(new ArrayList<>(capabilities));
    }

    /**
     * Build a set from requests, generating one grant per request.
     *
     * @param prefix     prefix for generated grant ids, e.g. {@code ns.default}
     * @param requests   the requests to grant
     * @return the grants
     */
    public static CapabilitySet grant(String prefix, Collection<CapabilityRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return EMPTY;
        }
        List<Capability> grants = new ArrayList<>(requests.size());
        for (CapabilityRequest request : requests) {
            grants.add(new Capability(prefix + ":" + request.kind().id(), request.kind(),
                    request.resource(), request.limits()));
        }
        return new CapabilitySet(grants);
    }

    /**
     * @return the synthetic capability set of the supervisor root: every kind, any resource,
     *         unbounded. Spawning intersects with it so the root itself grants nothing extra.
     */
    public static CapabilitySet root() {
        List<Capability> grants = new ArrayList<>(CapKind.values().length);
        for (CapKind kind : CapKind.values()) {
            grants.add(new Capability("root:" + kind.id(), kind, "*", Constraints.unbounded()));
        }
        return new CapabilitySet(grants);
    }

    /** @return the grants, in insertion order. */
    public List<Capability> capabilities() {
        return capabilities;
    }

    /** @return the kinds present in this set. */
    public Set<CapKind> kinds() {
        Set<CapKind> out = new LinkedHashSet<>();
        for (Capability capability : capabilities) {
            out.add(capability.kind());
        }
        return out;
    }

    /** @return whether no grant is present. */
    public boolean isEmpty() {
        return capabilities.isEmpty();
    }

    /** @return the number of grants. */
    public int size() {
        return capabilities.size();
    }

    /** @return whether any grant covers {@code kind}. */
    public boolean containsKind(CapKind kind) {
        for (Capability capability : capabilities) {
            if (capability.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /** @return whether any grant satisfies the request. */
    public boolean contains(CapabilityRequest request) {
        for (Capability capability : capabilities) {
            if (capability.covers(request)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether every request is satisfied. */
    public boolean containsAll(Collection<CapabilityRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return true;
        }
        for (CapabilityRequest request : requests) {
            if (!contains(request)) {
                return false;
            }
        }
        return true;
    }

    /** @return the grants covering {@code kind}, in order. */
    public List<Capability> select(CapKind kind) {
        List<Capability> out = new ArrayList<>();
        for (Capability capability : capabilities) {
            if (capability.kind() == kind) {
                out.add(capability);
            }
        }
        return out;
    }

    /** @return the grant with this id, if present. */
    public java.util.Optional<Capability> byId(String capId) {
        for (Capability capability : capabilities) {
            if (capability.capId().equals(capId)) {
                return java.util.Optional.of(capability);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Intersect two sets, keeping the narrower scope and limits of each overlapping pair.
     *
     * @param other the set to intersect with
     * @return a new set; empty when nothing overlaps
     */
    public CapabilitySet intersect(CapabilitySet other) {
        Objects.requireNonNull(other, "other");
        Map<String, Capability> out = new LinkedHashMap<>();
        for (Capability mine : capabilities) {
            for (Capability theirs : other.capabilities) {
                if (mine.kind() != theirs.kind()) {
                    continue;
                }
                if (!Capability.overlaps(mine.resource(), theirs.resource())) {
                    continue;
                }
                Capability narrower = narrower(mine, theirs);
                out.putIfAbsent(narrower.capId(), new Capability(narrower.capId(), narrower.kind(),
                        narrower.resource(), mine.limits().intersect(theirs.limits())));
            }
        }
        return out.isEmpty() ? EMPTY : new CapabilitySet(new ArrayList<>(out.values()));
    }

    /** @return the grant with the narrower scope; ties keep {@code a}. */
    private static Capability narrower(Capability a, Capability b) {
        if (Capability.coversResource(a.resource(), b.resource())
                && !Capability.coversResource(b.resource(), a.resource())) {
            return b;
        }
        return a;
    }

    /**
     * Replace grant ids, e.g. to give a freshly spawned agent its own ids while the derivation
     * graph records where each came from.
     *
     * @param ids one id per grant, in {@link #capabilities()} order
     * @return a copy carrying the new ids
     */
    public CapabilitySet reId(List<String> ids) {
        if (ids.size() != capabilities.size()) {
            throw new IllegalArgumentException("expected " + capabilities.size() + " ids, got " + ids.size());
        }
        List<Capability> out = new ArrayList<>(capabilities.size());
        for (int i = 0; i < capabilities.size(); i++) {
            out.add(capabilities.get(i).withCapId(ids.get(i)));
        }
        return new CapabilitySet(out);
    }

    /**
     * Drop one grant by id.
     *
     * @param capId the grant id
     * @return a copy without that grant
     */
    public CapabilitySet without(String capId) {
        List<Capability> out = new ArrayList<>(capabilities.size());
        for (Capability capability : capabilities) {
            if (!capability.capId().equals(capId)) {
                out.add(capability);
            }
        }
        return out.size() == capabilities.size() ? this : new CapabilitySet(out);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CapabilitySet other && capabilities.equals(other.capabilities);
    }

    @Override
    public int hashCode() {
        return capabilities.hashCode();
    }

    @Override
    public String toString() {
        return capabilities.toString();
    }
}
