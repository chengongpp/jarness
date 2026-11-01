package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.annotation.Mod;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.api.mod.ModContext;
import ch.predmet.jarness.api.preset.Preset;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises jar discovery, jnmods.toml parsing, and dynamic loading without a running runtime. */
class DynamicLoadTest {

    @TempDir
    Path base;

    /** The mod carried by the jar built in this test. */
    @Mod("test.jarmod")
    public static class JarMod implements JarnessMod {
        @Override
        public void register(ModContext ctx) {
            ctx.instruments().register("marker", true, 500, "marker instrument", ic -> {
            });
        }
    }

    /** A tool only referenced through the jar mod's instrument. */
    public static class JarTool implements Tool {
        @Override
        public ToolSpec spec() {
            return ToolSpec.of("jar_marker", "Marker tool from a dynamically built jar.");
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
            return ToolOutcome.ok("marker");
        }
    }

    private Path buildJar(String entrypoint) throws IOException {
        Path jar = base.resolve("jarmod.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/jnmods.toml"));
            out.write(("id = \"test.jarmod\"\nversion = \"1.2.3\"\nentrypoint = \"" + entrypoint + "\"\n")
                    .getBytes());
            out.closeEntry();
            for (Class<?> cls : new Class<?>[]{JarMod.class, JarTool.class}) {
                String resource = cls.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(resource));
                try (InputStream in = cls.getClassLoader().getResourceAsStream(resource)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void loadsAJarModDynamically() throws IOException {
        Path jar = buildJar(JarMod.class.getName());
        try (ModLoaderImpl loader = new ModLoaderImpl(JarnessLayout.of(base), () -> null)) {
            LoadReport report = loader.loadJar(jar, true);
            assertTrue(report.ok(), report::render);
            assertEquals(1, report.loaded().size());
            assertEquals("test.jarmod", report.loaded().get(0).descriptor().id());
            assertEquals("1.2.3", report.loaded().get(0).descriptor().version());
            assertEquals(jar, report.loaded().get(0).file());
            assertTrue(loader.instrument("marker").isPresent());
            assertEquals(1, report.applied().size());
        }
    }

    @Test
    void duplicateLoadIsAnError() throws IOException {
        Path jar = buildJar(JarMod.class.getName());
        try (ModLoaderImpl loader = new ModLoaderImpl(JarnessLayout.of(base), () -> null)) {
            assertTrue(loader.loadJar(jar, true).ok());
            LoadReport second = loader.loadJar(jar, true);
            assertFalse(second.ok());
            assertTrue(second.render().contains("test.jarmod"));
        }
    }

    @Test
    void directoryDiscoveryFindsJars() throws IOException {
        Path modsDir = JarnessLayout.of(base).modsDir();
        Files.createDirectories(modsDir);
        Files.copy(buildJar(JarMod.class.getName()), modsDir.resolve("jarmod.jar"));
        Files.writeString(modsDir.resolve("README.txt"), "not scanned");
        try (TestRuntime runtime = new TestRuntime(base)) {
            LoadReport report = runtime.loader.loadAll(Preset.all("default"));
            assertTrue(report.ok(), report::render);
            assertTrue(report.loadedIds().contains("jarness.core"), report::render);
            assertTrue(report.loadedIds().contains("test.jarmod"), report::render);
            assertTrue(runtime.tools.contains("read"), "core instruments should have applied");
            assertEquals(5, runtime.templates.ids().size());
        }
    }

    /** A minimal runtime that gives system-mod instruments real registries to apply against. */
    private static final class TestRuntime implements ch.predmet.jarness.api.JarnessRuntime {
        final JarnessLayout layout;
        final ch.predmet.jarness.api.config.JarnessConfig config;
        final ch.predmet.jarness.core.tool.ToolRegistryImpl tools = new ch.predmet.jarness.core.tool.ToolRegistryImpl();
        final ch.predmet.jarness.core.agent.AgentTemplateRegistryImpl templates =
                new ch.predmet.jarness.core.agent.AgentTemplateRegistryImpl();
        final ch.predmet.jarness.core.provider.ProviderRegistryImpl providers =
                new ch.predmet.jarness.core.provider.ProviderRegistryImpl();
        final ch.predmet.jarness.core.job.JobRegistryImpl jobs = new ch.predmet.jarness.core.job.JobRegistryImpl();
        final ch.predmet.jarness.core.hook.HookBusImpl hooks;
        final ModLoaderImpl loader;

        TestRuntime(Path base) {
            this.layout = JarnessLayout.of(base);
            this.config = ch.predmet.jarness.api.config.JarnessConfig.defaults(layout);
            this.hooks = new ch.predmet.jarness.core.hook.HookBusImpl(() -> this);
            this.loader = new ModLoaderImpl(layout, () -> this);
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public JarnessLayout layout() {
            return layout;
        }

        @Override
        public ch.predmet.jarness.api.config.JarnessConfig config() {
            return config;
        }

        @Override
        public ch.predmet.jarness.api.mod.ModLoader modLoader() {
            return loader;
        }

        @Override
        public ch.predmet.jarness.api.supervisor.Supervisor supervisor() {
            return null;
        }

        @Override
        public ch.predmet.jarness.api.session.SessionStore sessions() {
            return null;
        }

        @Override
        public ch.predmet.jarness.core.provider.ProviderRegistryImpl providers() {
            return providers;
        }

        @Override
        public ch.predmet.jarness.core.tool.ToolRegistryImpl tools() {
            return tools;
        }

        @Override
        public ch.predmet.jarness.core.agent.AgentTemplateRegistryImpl templates() {
            return templates;
        }

        @Override
        public ch.predmet.jarness.core.hook.HookBusImpl hooks() {
            return hooks;
        }

        @Override
        public ch.predmet.jarness.api.instrument.InstrumentRegistry instruments() {
            return loader.instrumentRegistry();
        }

        @Override
        public ch.predmet.jarness.core.job.JobRegistryImpl jobs() {
            return jobs;
        }

        @Override
        public ch.predmet.jarness.api.namespace.NamespaceRegistry namespaces() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ch.predmet.jarness.api.exec.ExecutionRegistry execution() {
            return new ch.predmet.jarness.core.exec.ExecutionRegistryImpl();
        }

        @Override
        public ch.predmet.jarness.api.approval.ApprovalRegistry approvals() {
            return new ch.predmet.jarness.core.approval.ApprovalRegistryImpl();
        }

        @Override
        public ch.predmet.jarness.api.ipc.IpcRegistry ipc() {
            return new ch.predmet.jarness.core.ipc.IpcRegistryImpl();
        }

        @Override
        public ch.predmet.jarness.api.registry.Registry registry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ch.predmet.jarness.api.observe.Observation observation() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ch.predmet.jarness.api.loop.LoopHookRegistry loopHooks() {
            return new ch.predmet.jarness.core.loop.LoopHookRegistryImpl();
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void start() {
        }

        @Override
        public ch.predmet.jarness.api.agent.Agent spawn(ch.predmet.jarness.api.agent.AgentRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ch.predmet.jarness.api.agent.Agent spawnFrom(
                ch.predmet.jarness.api.agent.Agent parent,
                ch.predmet.jarness.api.agent.AgentRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            loader.close();
            jobs.shutdown();
        }
    }

    @Test
    void corruptJarIsAPreciseParseError() throws IOException {
        Path modsDir = JarnessLayout.of(base).modsDir();
        Files.createDirectories(modsDir);
        Files.writeString(modsDir.resolve("broken.jar"), "this is not a zip");
        try (ModLoaderImpl loader = new ModLoaderImpl(JarnessLayout.of(base), () -> null)) {
            LoadReport report = loader.loadAll(Preset.all("default"));
            assertFalse(report.ok());
            assertTrue(report.render().contains("broken.jar"), report::render);
        }
    }
}
