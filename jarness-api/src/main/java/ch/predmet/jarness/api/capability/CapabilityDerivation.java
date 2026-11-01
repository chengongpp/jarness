package ch.predmet.jarness.api.capability;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The derivation DAG behind capability grants: which grant came from which parent grant.
 *
 * <p>Revocation cascades along these edges, so revoking a namespace-level grant also revokes every
 * child grant derived from it, in every agent. The graph is deliberately separate from the
 * immutable {@link CapabilitySet}: the set answers "what may I do now", the graph answers "where did
 * it come from and what dies with it".</p>
 */
public final class CapabilityDerivation {

    private final Map<String, String> parentOf = new ConcurrentHashMap<>();
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    /**
     * Record that {@code capId} was derived from {@code parentCapId}.
     *
     * @param capId       the child grant
     * @param parentCapId the parent grant, or {@code null} for a root grant
     */
    public void add(String capId, String parentCapId) {
        if (capId == null || capId.isBlank()) {
            return;
        }
        if (parentCapId == null || parentCapId.isBlank()) {
            parentOf.remove(capId);
        } else {
            parentOf.put(capId, parentCapId);
        }
    }

    /**
     * Revoke a grant and every grant derived from it.
     *
     * @param capId the grant to revoke
     * @return every revoked grant id, including {@code capId} itself
     */
    public Set<String> revoke(String capId) {
        Set<String> out = descendants(capId);
        out.add(capId);
        revoked.addAll(out);
        return out;
    }

    /**
     * @param capId a grant id
     * @return every grant derived from it, directly or transitively, excluding itself
     */
    public Set<String> descendants(String capId) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(capId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Map.Entry<String, String> entry : parentOf.entrySet()) {
                if (current.equals(entry.getValue()) && out.add(entry.getKey())) {
                    queue.add(entry.getKey());
                }
            }
        }
        return out;
    }

    /**
     * @param capId a grant id
     * @return whether the grant and all its ancestors are still valid
     */
    public boolean valid(String capId) {
        String current = capId;
        Set<String> seen = new LinkedHashSet<>();
        while (current != null && seen.add(current)) {
            if (revoked.contains(current)) {
                return false;
            }
            current = parentOf.get(current);
        }
        return true;
    }

    /**
     * Forget a grant id entirely, e.g. after the owning agent stopped.
     *
     * @param capId the grant id
     */
    public void forget(String capId) {
        parentOf.remove(capId);
        revoked.remove(capId);
    }

    /** @return whether any grant has been revoked. */
    public boolean hasRevocations() {
        return !revoked.isEmpty();
    }

    /** @return the revoked grant ids. */
    public Set<String> revoked() {
        return Set.copyOf(revoked);
    }

    /** @return the number of derivation edges. */
    public int edgeCount() {
        return parentOf.size();
    }
}
