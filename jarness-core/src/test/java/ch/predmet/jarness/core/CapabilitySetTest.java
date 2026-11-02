package ch.predmet.jarness.core;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilityDerivation;
import ch.predmet.jarness.api.capability.CapabilityRequest;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.capability.Constraints;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilitySetTest {

    @Test
    void resourceScopesAreContainedNarrowly() {
        assertTrue(Capability.coversResource("*", "/anything"));
        assertTrue(Capability.coversResource("/data/*", "/data/team/file.txt"));
        assertFalse(Capability.coversResource("/data/*", "/etc/passwd"));
        assertFalse(Capability.coversResource("/data/*", "/data-secret"),
                "a path wildcard must end at a path-component boundary");
        assertFalse(Capability.coversResource("/data/team", "/data/team/file.txt"),
                "a plain path is exact, not a prefix");
        assertTrue(Capability.coversResource("*.example.com", "api.example.com"));
        assertTrue(Capability.coversResource("*.example.com", "example.com"));
        assertFalse(Capability.coversResource("*.example.com", "evil-example.com"));
    }

    @Test
    void containsChecksKindResourceAndLimits() {
        CapabilitySet held = CapabilitySet.of(new Capability("c1", CapKind.FS_READ, "/data/*",
                new Constraints("/data/*", 100, null, -1)));
        assertTrue(held.contains(new CapabilityRequest(CapKind.FS_READ, "/data/team",
                new Constraints("/data/team", 100, null, -1))));
        assertTrue(held.contains(new CapabilityRequest(CapKind.FS_READ, "/data/team",
                new Constraints("/data/team", 50, null, -1))));
        assertFalse(held.contains(CapabilityRequest.of(CapKind.FS_READ, "/etc")));
        assertFalse(held.contains(CapabilityRequest.of(CapKind.FS_WRITE, "/data/team")));
        assertFalse(held.contains(new CapabilityRequest(CapKind.FS_READ, "/data/team",
                new Constraints("/data/team", 200, null, -1))),
                "a request that allows more tokens than the grant must not be covered");
        assertFalse(held.contains(CapabilityRequest.of(CapKind.FS_READ, "/data/team")),
                "an unbounded request is not covered by a bounded grant");
    }

    @Test
    void intersectionKeepsTheNarrowerScopeAndLimits() {
        CapabilitySet parent = CapabilitySet.of(new Capability("p", CapKind.FS_WRITE, "*",
                new Constraints("*", 100, Instant.parse("2030-01-01T00:00:00Z"), 4)));
        CapabilitySet namespace = CapabilitySet.of(new Capability("n", CapKind.FS_WRITE, "/workspace/*",
                new Constraints("/workspace/*", 50, null, -1)));
        CapabilitySet effective = parent.intersect(namespace);

        assertEquals(1, effective.size());
        Capability grant = effective.capabilities().get(0);
        assertEquals("/workspace/*", grant.resource());
        assertEquals(50, grant.limits().maxTokens());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), grant.limits().deadline());
        assertTrue(effective.contains(new CapabilityRequest(CapKind.FS_WRITE, "/workspace/notes.txt",
                new Constraints("/workspace/notes.txt", 50,
                        Instant.parse("2030-01-01T00:00:00Z"), 4))));
        assertFalse(effective.contains(CapabilityRequest.of(CapKind.FS_WRITE, "/etc/passwd")));
    }

    @Test
    void disjointScopesDoNotIntersect() {
        CapabilitySet a = CapabilitySet.of(Capability.of(CapKind.FS_READ, "/a/*"));
        CapabilitySet b = CapabilitySet.of(Capability.of(CapKind.FS_READ, "/b/*"));
        assertTrue(a.intersect(b).isEmpty());
    }

    @Test
    void derivationRevokesTheWholeSubtree() {
        CapabilityDerivation dag = new CapabilityDerivation();
        dag.add("root", null);
        dag.add("child", "root");
        dag.add("grandchild", "child");
        dag.add("other", null);

        assertEquals(Set.of("child", "grandchild"), dag.descendants("root"));
        Set<String> revoked = dag.revoke("root");
        assertEquals(Set.of("root", "child", "grandchild"), revoked);
        assertFalse(dag.valid("child"));
        assertFalse(dag.valid("grandchild"));
        assertTrue(dag.valid("other"));
    }

    @Test
    void rootSetGrantsEveryKind() {
        CapabilitySet root = CapabilitySet.root();
        for (CapKind kind : CapKind.values()) {
            assertTrue(root.containsKind(kind), () -> "missing " + kind);
        }
        assertEquals(CapKind.values().length, root.size());
    }
}
