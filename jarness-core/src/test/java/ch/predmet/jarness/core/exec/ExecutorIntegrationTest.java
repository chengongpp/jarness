package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.exec.ExecRequest;
import ch.predmet.jarness.api.exec.ExecResult;
import ch.predmet.jarness.api.exec.ExecutionException;
import ch.predmet.jarness.api.tool.ToolMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests against the compiled Rust executor. Skipped when the binary has not been built.
 */
class ExecutorIntegrationTest {

    @TempDir
    static Path workspace;

    private static LocalExecutionProvider provider;
    private static String previous;

    @BeforeAll
    static void start() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path binary = repo.resolve("dist/executor/jarness-executor");
        assumeTrue(Files.isExecutable(binary), "executor binary not built; run `make executor`");
        previous = System.getProperty("jarness.executor");
        System.setProperty("jarness.executor", binary.toString());
        provider = LocalExecutionProvider.start(repo);
    }

    @AfterAll
    static void stop() {
        if (provider != null) {
            provider.close();
        }
        if (previous == null) {
            System.clearProperty("jarness.executor");
        } else {
            System.setProperty("jarness.executor", previous);
        }
    }

    private static CapabilitySet caps(CapKind... kinds) {
        java.util.List<Capability> caps = new java.util.ArrayList<>();
        for (CapKind kind : kinds) {
            caps.add(Capability.of(kind));
        }
        return CapabilitySet.of(caps);
    }

    private static ExecRequest request(String callId, String tool, Map<String, Object> args, CapabilitySet caps,
                                       String sandbox) {
        return new ExecRequest(callId, tool, args, null, ToolMode.SYNC, callId,
                workspace.toAbsolutePath().toString(), sandbox, caps, "default");
    }

    @Test
    void writesReadsAndExecutes() {
        CapabilitySet full = caps(CapKind.FS_READ, CapKind.FS_WRITE, CapKind.EXEC);
        ExecResult write = provider.invoke(request("c1", "fs.write",
                Map.of("path", "nested/hello.txt", "content", "hello executor"), full, "workspace-write"));
        assertTrue(write.ok(), write.error());
        assertTrue(Files.exists(workspace.resolve("nested/hello.txt")));

        ExecResult read = provider.invoke(request("c2", "fs.read",
                Map.of("path", "nested/hello.txt"), full, "workspace-write"));
        assertTrue(read.ok(), read.error());
        assertTrue(read.text().contains("hello executor"), read.text());

        ExecResult exec = provider.invoke(request("c3", "exec.run",
                Map.of("command", "printf 'exec-ok'"), full, "workspace-write"));
        assertTrue(exec.ok(), exec.error());
        assertTrue(exec.text().contains("exec-ok"), exec.text());
        assertTrue(exec.text().contains("[exit code: 0]"), exec.text());
    }

    @Test
    void capabilityAndSandboxAreEnforcedInTheExecutor() {
        CapabilitySet readOnly = caps(CapKind.FS_READ);
        ExecResult denied = provider.invoke(request("d1", "fs.write",
                Map.of("path", "denied.txt", "content", "nope"), readOnly, "workspace-write"));
        assertFalse(denied.ok());
        assertTrue(denied.error().contains("capability denied"), denied.error());
        assertFalse(Files.exists(workspace.resolve("denied.txt")));

        CapabilitySet full = caps(CapKind.FS_READ, CapKind.FS_WRITE);
        ExecResult escape = provider.invoke(request("d2", "fs.write",
                Map.of("path", "../outside.txt", "content", "nope"), full, "workspace-write"));
        assertFalse(escape.ok());
        assertTrue(escape.error().contains("outside the workspace"), escape.error());
    }

    @Test
    void providerDeadlineCancelsTheCall() {
        CapabilitySet full = caps(CapKind.EXEC);
        ExecRequest request = new ExecRequest("t1", "exec.run", Map.of("command", "sleep 5"),
                java.time.Instant.now().plusMillis(150), ToolMode.SYNC, "t1",
                workspace.toString(), "workspace-write", full, "default");
        ExecResult result = provider.invoke(request);
        assertFalse(result.ok());
        assertTrue(result.error().contains("timeout"), result.error());
    }

    @Test
    void idempotencyKeyReturnsTheCachedResult() throws Exception {
        CapabilitySet full = caps(CapKind.FS_READ, CapKind.FS_WRITE);
        ExecResult first = provider.invoke(new ExecRequest("i1", "fs.write",
                Map.of("path", "idem.txt", "content", "one"), null, ToolMode.SYNC, "same-key",
                workspace.toString(), "workspace-write", full, "default"));
        assertTrue(first.ok(), first.error());
        Files.writeString(workspace.resolve("idem.txt"), "changed");
        ExecResult second = provider.invoke(new ExecRequest("i2", "fs.write",
                Map.of("path", "idem.txt", "content", "two"), null, ToolMode.SYNC, "same-key",
                workspace.toString(), "workspace-write", full, "default"));
        assertTrue(second.ok(), second.error());
        assertEquals("changed", read(workspace.resolve("idem.txt")));
    }

    @Test
    void resourceScopesUsePathComponentsAndContainSymlinks() throws Exception {
        Path scope = Files.createDirectories(workspace.resolve("scope"));
        Path outside = Files.writeString(workspace.resolve("outside.txt"), "outside secret");
        Path sibling = Files.writeString(workspace.resolve("scope-secret.txt"), "sibling secret");
        Files.createSymbolicLink(scope.resolve("link.txt"), outside);
        CapabilitySet scoped = CapabilitySet.of(
                new Capability("scope-read", CapKind.FS_READ, scope + "/*", null),
                new Capability("scope-write", CapKind.FS_WRITE, scope + "/*", null));

        ExecResult prefix = provider.invoke(new ExecRequest("prefix", "fs.read",
                Map.of("path", sibling.toString()), null, ToolMode.SYNC, "prefix",
                workspace.toString(), "full-access", scoped, "default"));
        assertFalse(prefix.ok(), "a /scope/* grant must not cover /scope-secret.txt");

        ExecResult linkedRead = provider.invoke(new ExecRequest("linked-read", "fs.read",
                Map.of("path", scope.resolve("link.txt").toString()), null, ToolMode.SYNC, "linked-read",
                workspace.toString(), "workspace-write", scoped, "default"));
        assertFalse(linkedRead.ok(), "a scoped read must not follow a symlink outside its root");

        ExecResult linkedWrite = provider.invoke(new ExecRequest("linked-write", "fs.write",
                Map.of("path", scope.resolve("link.txt").toString(), "content", "overwritten"),
                null, ToolMode.SYNC, "linked-write", workspace.toString(), "workspace-write",
                scoped, "default"));
        assertFalse(linkedWrite.ok(), "a scoped write must not follow a symlink outside its root");
        assertEquals("outside secret", Files.readString(outside));

        ExecResult newFile = provider.invoke(new ExecRequest("new-scoped-file", "fs.write",
                Map.of("path", scope.resolve("nested/new.txt").toString(), "content", "safe"),
                null, ToolMode.SYNC, "new-scoped-file", workspace.toString(), "workspace-write",
                scoped, "default"));
        assertTrue(newFile.ok(), newFile.error());
        assertEquals("safe", Files.readString(scope.resolve("nested/new.txt")));

        ExecResult glob = provider.invoke(new ExecRequest("scoped-glob", "fs.glob",
                Map.of("path", scope.toString(), "pattern", "*"), null, ToolMode.SYNC,
                "scoped-glob", workspace.toString(), "workspace-write", scoped, "default"));
        assertTrue(glob.ok(), glob.error());
        assertFalse(glob.text().contains("link.txt"), "recursive search must not follow symlinks");

        ExecResult grep = provider.invoke(new ExecRequest("scoped-grep", "fs.grep",
                Map.of("path", scope.toString(), "pattern", "outside secret"), null, ToolMode.SYNC,
                "scoped-grep", workspace.toString(), "workspace-write", scoped, "default"));
        assertTrue(grep.ok(), grep.error());
        assertFalse(grep.text().contains("outside secret"), "grep must not read the symlink target");
    }

    @Test
    void symlinkedWorkdirCannotEscapeWorkspace() throws Exception {
        Path scope = Files.createDirectories(workspace.resolve("exec-scope"));
        Path outside = Files.createTempDirectory("jarness-exec-outside");
        try {
            Files.createSymbolicLink(scope.resolve("linked-dir"), outside);
            ExecResult result = provider.invoke(new ExecRequest("symlink-workdir", "exec.run",
                    Map.of("command", "pwd", "workdir", scope.resolve("linked-dir").toString()),
                    null, ToolMode.SYNC, "symlink-workdir", workspace.toString(), "workspace-write",
                    caps(CapKind.EXEC), "default"));
            assertFalse(result.ok());
            assertTrue(result.error().contains("outside the workspace"), result.error());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void duplicateInFlightCallIdIsRejectedInsteadOfCrossTalking() throws Exception {
        CapabilitySet full = caps(CapKind.EXEC);
        CompletableFuture<ExecResult> first = CompletableFuture.supplyAsync(() -> provider.invoke(
                request("duplicate", "exec.run", Map.of("command", "printf FIRST; sleep 0.3"),
                        full, "workspace-write")));
        Thread.sleep(75);
        ExecutionException duplicate = assertThrows(ExecutionException.class, () -> provider.invoke(
                request("duplicate", "exec.run", Map.of("command", "printf SECOND"),
                        full, "workspace-write")));
        assertEquals(ExecutionException.Reason.REJECTED, duplicate.reason());
        assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());
        assertTrue(first.get(5, TimeUnit.SECONDS).text().contains("FIRST"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
