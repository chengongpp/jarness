package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;
import ch.predmet.jarness.api.config.JarnessConfig;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.hook.HookBus;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.instrument.InstrumentRegistry;
import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.api.mod.ModLoader;
import ch.predmet.jarness.api.preset.Preset;
import ch.predmet.jarness.api.provider.ProviderRegistry;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.supervisor.Supervisor;
import ch.predmet.jarness.api.tool.ToolRegistry;
import ch.predmet.jarness.core.agent.AgentTemplateRegistryImpl;
import ch.predmet.jarness.core.agent.SupervisorImpl;
import ch.predmet.jarness.core.config.ConfigLoader;
import ch.predmet.jarness.core.config.PresetLoader;
import ch.predmet.jarness.core.hook.HookBusImpl;
import ch.predmet.jarness.core.job.JobRegistryImpl;
import ch.predmet.jarness.core.mod.ModLoaderImpl;
import ch.predmet.jarness.core.provider.ProviderRegistryImpl;
import ch.predmet.jarness.core.session.SqliteSessionStore;
import ch.predmet.jarness.core.tool.ToolContextImpl;
import ch.predmet.jarness.core.tool.ToolRegistryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The runtime implementation: wires the registries together and owns the lifecycle order —
 * the supervisor comes up first and goes down last, the mod loader closes last of all.
 */
public final class JarnessRuntimeImpl implements JarnessRuntime {

    private static final Logger log = LoggerFactory.getLogger(JarnessRuntimeImpl.class);

    private final JarnessLayout layout;
    private final JarnessConfig config;
    private final String presetName;

    private final HookBusImpl hookBus;
    private final ToolRegistryImpl toolRegistry;
    private final AgentTemplateRegistryImpl templateRegistry;
    private final ProviderRegistryImpl providerRegistry;
    private final JobRegistryImpl jobRegistry;
    private final SessionStore sessionStore;
    private final ModLoaderImpl modLoader;
    private final SupervisorImpl supervisor;

    private volatile boolean running;

    JarnessRuntimeImpl(JarnessLayout layout, String presetName) {
        this.layout = layout;
        this.config = ConfigLoader.load(layout);
        this.presetName = presetName;
        this.hookBus = new HookBusImpl(() -> this);
        this.toolRegistry = new ToolRegistryImpl();
        this.templateRegistry = new AgentTemplateRegistryImpl();
        this.providerRegistry = new ProviderRegistryImpl();
        this.jobRegistry = new JobRegistryImpl();
        this.sessionStore = new SqliteSessionStore(config.sessionStoreUrl());
        this.modLoader = new ModLoaderImpl(layout, () -> this);
        this.supervisor = new SupervisorImpl(() -> this,
                (agent, cancelled, cancelReason) -> new ToolContextImpl(agent, this, cancelled, cancelReason));
    }

    @Override
    public String version() {
        return Jarness.VERSION;
    }

    @Override
    public JarnessLayout layout() {
        return layout;
    }

    @Override
    public JarnessConfig config() {
        return config;
    }

    @Override
    public ModLoader modLoader() {
        return modLoader;
    }

    @Override
    public Supervisor supervisor() {
        return supervisor;
    }

    @Override
    public SessionStore sessions() {
        return sessionStore;
    }

    @Override
    public ProviderRegistry providers() {
        return providerRegistry;
    }

    @Override
    public ToolRegistry tools() {
        return toolRegistry;
    }

    @Override
    public AgentTemplateRegistry templates() {
        return templateRegistry;
    }

    @Override
    public HookBus hooks() {
        return hookBus;
    }

    @Override
    public InstrumentRegistry instruments() {
        return modLoader.instrumentRegistry();
    }

    @Override
    public JobRegistry jobs() {
        return jobRegistry;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        // The supervisor lives at least once and is the first thing up.
        if (config.autoStartSupervisor()) {
            supervisor.start();
        }
        Preset preset = resolvePreset();
        LoadReport report = modLoader.loadAll(preset);
        if (!report.ok()) {
            throw new IllegalStateException("mod loading failed:\n" + report.render());
        }
        if (!report.warnings().isEmpty()) {
            report.warnings().forEach(w -> log.warn("mods: {}", w));
        }
        running = true;
        hookBus.emit(HookPoint.RUNTIME_START, this);
        log.info("jarness {} started (base {}, preset {}, mods {})",
                version(), layout.baseDir(), preset.name(), report.loadedIds());
    }

    private Preset resolvePreset() {
        String name = presetName != null && !presetName.isBlank() ? presetName : config.preset();
        return PresetLoader.load(layout, name).orElseThrow(() -> {
            List<String> available = PresetLoader.all(layout).stream().map(Preset::name).toList();
            return new IllegalStateException("preset \"" + name + "\" not found; available: " + available
                    + " (looked in " + layout.presetsDir() + " and builtin presets)");
        });
    }

    @Override
    public Agent spawn(AgentRequest request) {
        if (!running) {
            throw new IllegalStateException("runtime is not started");
        }
        return supervisor.spawn(request);
    }

    @Override
    public synchronized void close() {
        if (!running && !supervisor.isRunning()) {
            return;
        }
        running = false;
        try {
            hookBus.emit(HookPoint.RUNTIME_STOP, this);
        } catch (RuntimeException e) {
            log.warn("RUNTIME_STOP hook failed", e);
        }
        // Supervisor goes down before anything it might still need.
        supervisor.shutdown();
        for (var provider : providerRegistry.all()) {
            try {
                provider.close();
            } catch (RuntimeException e) {
                log.warn("provider {} failed to close", provider.id(), e);
            }
        }
        try {
            sessionStore.close();
        } catch (RuntimeException e) {
            log.warn("session store failed to close", e);
        }
        jobRegistry.shutdown();
        // The mod loader is released last: it owns the mod classloaders.
        modLoader.close();
        log.info("jarness stopped");
    }
}
