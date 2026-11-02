package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilityRequest;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.kernel.AgentManifest;
import ch.predmet.jarness.api.kernel.AgentTree;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.kernel.ExitStatus;
import ch.predmet.jarness.api.kernel.ResourceStats;
import ch.predmet.jarness.api.kernel.SpawnDeniedException;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceMetadata;
import ch.predmet.jarness.api.namespace.NamespaceSpec;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.core.session.SqliteSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 tests for the supervision tree, namespace binding, capability attenuation, and budgets.
 *
 * <p>These tests never call a model: spawning, forking, revocation, and parent death are all
 * supervisor-level behaviors. Turns are exercised by the live integration test instead.</p>
 */
class SupervisorTreeTest {

    @TempDir
    Path base;

    @Test
    void namespacesCanBeDeclaredInConfig() throws IOException {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [namespaces.review]
                template = "void"
                capabilities = ["fs.read", "exec"]
                """);
        try (JarnessRuntime runtime = Jarness.Builder.at(base).build()) {
            runtime.start();
            assertTrue(runtime.namespaces().exists("review"));
            Namespace review = runtime.namespaces().get("review");
            assertEquals("void", review.defaultTemplateId());
            assertEquals(java.util.Set.of(CapKind.FS_READ, CapKind.EXEC),
                    review.metadata().defaults().kinds());
            assertTrue(runtime.namespaces().defaultNs().isDefault());
        }
    }

    @Test
    void unknownTemplateAndVersionAreDeniedPrecisely() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            long rootPid = root(runtime, "lead");

            SpawnDeniedException missing = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(rootPid, null,
                            AgentManifest.builder("does-not-exist").build(), null, null));
            assertEquals(SpawnDeniedException.Reason.TEMPLATE, missing.reason());

            SpawnDeniedException version = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(rootPid, null,
                            AgentManifest.builder("void").templateVersion("9").build(), null, null));
            assertEquals(SpawnDeniedException.Reason.TEMPLATE, version.reason());
            assertTrue(version.getMessage().contains("version"));
        }
    }

    private JarnessRuntime runtime(String extraConfig) throws IOException {
        JarnessLayout layout = JarnessLayout.of(base);
        Files.createDirectories(layout.configDir());
        Files.writeString(layout.configFile(), """
                [defaults]
                template = "void"

                [supervisor]
                auto_start = true
                """ + (extraConfig == null ? "" : extraConfig) + """

                [execution]
                mode = "local"
                """);
        JarnessRuntime runtime = Jarness.Builder.at(base).build();
        runtime.start();
        return runtime;
    }

    private static long root(JarnessRuntime runtime, String name) {
        return runtime.supervisor().spawn(0, "default",
                AgentManifest.builder("void").name(name).build(), null, null);
    }

    @Test
    void treeTracksParentsDepthAndStats() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            long rootPid = root(runtime, "lead");
            long childPid = runtime.supervisor().spawn(rootPid, null,
                    AgentManifest.builder("void").name("coder").build(), null, null);

            Agent lead = runtime.supervisor().agent(rootPid);
            Agent coder = runtime.supervisor().agent(childPid);
            assertEquals(0L, lead.parentPid());
            assertEquals(1, lead.depth());
            assertEquals(rootPid, coder.parentPid());
            assertEquals(2, coder.depth());
            assertEquals("default", coder.namespaceId());

            AgentTree tree = runtime.supervisor().tree(rootPid);
            assertEquals(2, tree.size());
            assertEquals(childPid, tree.children().get(0).pid());
            assertEquals(3, runtime.supervisor().tree(0).size(), "root pid 0 + lead + coder");

            ResourceStats stats = runtime.supervisor().stats(rootPid);
            assertEquals(1, stats.children());
            assertEquals(1, stats.descendants());
            assertEquals(1, stats.depth());
            assertEquals(0, stats.turns());
        }
    }

    @Test
    void capabilitiesAreAttenuatedByParentAndNamespace() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            long rootPid = root(runtime, "lead");
            long childPid = runtime.supervisor().spawn(rootPid, null,
                    AgentManifest.builder("void").name("reader").build(),
                    CapabilitySet.of(Capability.of(CapKind.FS_READ)), null);

            Agent child = runtime.supervisor().agent(childPid);
            assertTrue(child.capabilities().containsKind(CapKind.FS_READ));
            assertFalse(child.capabilities().containsKind(CapKind.FS_WRITE));
            assertFalse(child.capabilities().containsKind(CapKind.SPAWN));

            SpawnDeniedException denied = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(childPid, null,
                            AgentManifest.builder("void").name("grandchild").build(), null, null));
            assertEquals(SpawnDeniedException.Reason.CAPABILITY, denied.reason());
        }
    }

    @Test
    void depthLimitIsEnforced() throws IOException {
        try (JarnessRuntime runtime = runtime("max_depth = 2\n")) {
            long rootPid = root(runtime, "lead");
            long childPid = runtime.supervisor().spawn(rootPid, null,
                    AgentManifest.builder("void").name("child").build(), null, null);

            SpawnDeniedException denied = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(childPid, null,
                            AgentManifest.builder("void").name("too-deep").build(), null, null));
            assertEquals(SpawnDeniedException.Reason.DEPTH, denied.reason());
            assertTrue(denied.getMessage().contains("depth 3"));
        }
    }

    @Test
    void childLimitIsEnforced() throws IOException {
        try (JarnessRuntime runtime = runtime("max_children = 1\n")) {
            long rootPid = root(runtime, "lead");
            runtime.supervisor().spawn(rootPid, null,
                    AgentManifest.builder("void").name("first").build(), null, null);

            SpawnDeniedException denied = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(rootPid, null,
                            AgentManifest.builder("void").name("second").build(), null, null));
            assertEquals(SpawnDeniedException.Reason.CHILDREN, denied.reason());
        }
    }

    @Test
    void crossingNamespacesRequiresTheNamespaceCapability() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            runtime.namespaces().create(new NamespaceSpec("other",
                    NamespaceMetadata.of("other", base.resolve("other-cwd"),
                            CapabilitySet.grant("ns.other",
                            List.of(CapabilityRequest.of(CapKind.FS_READ)))), runtime.sessions(), "void", ""));
            long rootPid = root(runtime, "lead");

            SpawnDeniedException denied = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(rootPid, "other",
                            AgentManifest.builder("void").name("intruder").build(), null, null));
            assertEquals(SpawnDeniedException.Reason.NAMESPACE, denied.reason());
            assertTrue(denied.getMessage().contains("may not cross"));

            assertTrue(runtime.supervisor().removeNamespace("other"));
            assertThrows(IllegalStateException.class, () -> runtime.supervisor().removeNamespace("default"));
        }
    }

    @Test
    void parentDeathKillsTheSubtree() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            long rootPid = root(runtime, "lead");
            long childPid = runtime.supervisor().spawn(rootPid, null,
                    AgentManifest.builder("void").name("coder").build(), null, null);
            Agent lead = runtime.supervisor().agent(rootPid);
            Agent coder = runtime.supervisor().agent(childPid);

            lead.stop("test-stop");

            Optional<ExitStatus> exit = coder.awaitExit(Duration.ofSeconds(5));
            assertTrue(exit.isPresent(), "child should have exited");
            assertEquals(ch.predmet.jarness.api.agent.AgentState.STOPPED, coder.state());
            assertEquals(ch.predmet.jarness.api.kernel.ExitKind.KILLED, exit.get().kind());
        }
    }

    @Test
    void budgetCannotExceedTheParentsRemaining() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            long rootPid = runtime.supervisor().spawn(0, "default",
                    AgentManifest.builder("void").name("lead").build(), null, Budget.tokens(10));

            SpawnDeniedException denied = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(rootPid, null,
                            AgentManifest.builder("void").name("greedy").build(), null, Budget.tokens(100)));
            assertEquals(SpawnDeniedException.Reason.BUDGET, denied.reason());

            long childPid = runtime.supervisor().spawn(rootPid, null,
                    AgentManifest.builder("void").name("frugal").build(), null, Budget.tokens(5));
            assertEquals(5, runtime.supervisor().agent(childPid).budget().tokensLeft());
        }
    }

    @Test
    void revocationCascadesToDescendants() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            long rootPid = root(runtime, "lead");
            long childPid = runtime.supervisor().spawn(rootPid, null,
                    AgentManifest.builder("void").name("coder").build(), null, null);
            Agent lead = runtime.supervisor().agent(rootPid);
            Agent coder = runtime.supervisor().agent(childPid);

            String readGrant = lead.capabilities().select(CapKind.FS_READ).get(0).capId();
            assertTrue(runtime.supervisor().revokeCap(readGrant));

            assertFalse(lead.capabilities().containsKind(CapKind.FS_READ));
            assertFalse(coder.capabilities().containsKind(CapKind.FS_READ));
            assertTrue(lead.capabilities().containsKind(CapKind.EXEC));
            assertFalse(runtime.supervisor().revokeCap("no-such-grant"));
        }
    }

    @Test
    void forkCopiesTheParentImage() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            long rootPid = root(runtime, "lead");
            Agent lead = runtime.supervisor().agent(rootPid);
            lead.session().append(Message.user("context before fork"));

            long forkedPid = runtime.supervisor().fork(rootPid, "default",
                    AgentManifest.builder(null).name("forked").modId("custom.mod")
                            .templateVersion("1").meta("marker", "kept").build(), null, null);
            Agent forked = runtime.supervisor().agent(forkedPid);

            assertNotEquals(lead.session().id(), forked.session().id());
            assertEquals(1, forked.session().messages().size());
            assertEquals("context before fork", forked.session().messages().get(0).text());
            assertEquals(ch.predmet.jarness.api.kernel.Origin.FORK, forked.manifest().origin());
            assertEquals("custom.mod", forked.manifest().modId());
            assertEquals("1", forked.manifest().templateVersion());
            assertEquals("kept", forked.manifest().metadata().get("marker"));
            assertEquals(rootPid, forked.parentPid());
        }
    }

    @Test
    void platformCapabilitiesCannotBeGrantedToOrdinaryAgents() throws IOException {
        try (JarnessRuntime runtime = runtime(null)) {
            runtime.namespaces().create(new NamespaceSpec("privileged",
                    NamespaceMetadata.of("privileged", base.resolve("privileged-cwd"),
                            CapabilitySet.grant("ns.privileged", List.of(
                                    CapabilityRequest.of(CapKind.PLATFORM),
                                    CapabilityRequest.of(CapKind.NAMESPACE)))),
                    runtime.sessions(), "void", ""));
            SpawnDeniedException denied = assertThrows(SpawnDeniedException.class,
                    () -> runtime.supervisor().spawn(0, "privileged",
                            AgentManifest.builder("void").build(), null, null));
            assertEquals(SpawnDeniedException.Reason.CAPABILITY, denied.reason());
            assertTrue(denied.getMessage().contains("pid-0"), denied.getMessage());
        }
    }

    @Test
    void namespacesCanUseAnIndependentStore() throws IOException {
        JarnessRuntime runtime = runtime(null);
        Path otherDb = base.resolve("other-ns.db");
        SqliteSessionStore otherStore = new SqliteSessionStore("jdbc:sqlite:" + otherDb);
        try {
            Namespace other = runtime.namespaces().create(new NamespaceSpec("iso",
                    NamespaceMetadata.of("iso", base.resolve("iso-cwd"),
                            CapabilitySet.grant("ns.iso",
                            List.of(CapabilityRequest.of(CapKind.FS_READ), CapabilityRequest.of(CapKind.SPAWN)))),
                    otherStore, "void", ""));
            long pid = runtime.supervisor().spawn(0, "iso",
                    AgentManifest.builder("void").name("isolated").build(), null, null);
            Agent agent = runtime.supervisor().agent(pid);

            assertEquals("iso", agent.namespaceId());
            assertEquals(other.id(), agent.namespace().id());
            assertTrue(otherStore.load(agent.session().id()).isPresent());
            assertTrue(runtime.sessions().load(agent.session().id()).isEmpty());

            IllegalStateException inUse = assertThrows(IllegalStateException.class,
                    () -> runtime.supervisor().removeNamespace("iso"));
            assertTrue(inUse.getMessage().contains("live agent"));
        } finally {
            runtime.close();
            otherStore.close();
        }
    }
}
