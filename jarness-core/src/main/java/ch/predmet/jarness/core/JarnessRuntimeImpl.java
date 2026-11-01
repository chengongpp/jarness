package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.CapabilityRequest;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.JarnessConfig;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.hook.HookBus;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.instrument.InstrumentRegistry;
import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.api.mod.ModLoader;
import ch.predmet.jarness.api.namespace.ModelProfile;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceMetadata;
import ch.predmet.jarness.api.namespace.NamespaceRegistry;
import ch.predmet.jarness.api.namespace.NamespaceSpec;
import ch.predmet.jarness.api.namespace.PolicySet;
import ch.predmet.jarness.api.preset.Preset;
import ch.predmet.jarness.api.provider.ModelRef;
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
import ch.predmet.jarness.core.namespace.NamespaceImpl;
import ch.predmet.jarness.core.namespace.NamespaceRegistryImpl;
import ch.predmet.jarness.core.provider.ProviderRegistryImpl;
import ch.predmet.jarness.core.session.LocalSessionStore;
import ch.predmet.jarness.core.tool.ToolContextImpl;
import ch.predmet.jarness.core.tool.ToolRegistryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime implementation: wires the registries together and owns the lifecycle order —
 * the supervisor comes up first and goes down last, the mod loader closes last of all.
 *
 * <p>Namespaces are built before the supervisor starts, because a supervisor must know the
 * environments it can spawn into. The default namespace uses the configured store; each configured
 * namespace may name its own store, default template, model profile, and capability set.</p>
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
    private final Map<String, SessionStore> stores;
    private final SessionStore sessionStore;
    private final NamespaceRegistryImpl namespaces;
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
        this.stores = new LinkedHashMap<>();
        this.sessionStore = store(config.sessionStoreUrl());
        this.namespaces = buildNamespaces(sessionStore);
        this.modLoader = new ModLoaderImpl(layout, () -> this);
        this.supervisor = new SupervisorImpl(() -> this,
                (agent, cancelled, cancelReason) -> new ToolContextImpl(agent, this, cancelled, cancelReason),
                namespaces, config.maxDepth(), config.maxChildren());
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
    public NamespaceRegistry namespaces() {
        return namespaces;
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
        warnAboutMissingTemplates();
        hookBus.emit(HookPoint.RUNTIME_START, this);
        log.info("jarness {} started (base {}, preset {}, namespaces {}, mods {})",
                version(), layout.baseDir(), preset.name(), namespaces.list().stream().map(Namespace::id).toList(),
                report.loadedIds());
    }

    @Override
    public Agent spawn(AgentRequest request) {
        if (!running) {
            throw new IllegalStateException("runtime is not started");
        }
        return supervisor.spawn(request);
    }

    @Override
    public Agent spawnFrom(Agent parent, AgentRequest request) {
        if (!running) {
            throw new IllegalStateException("runtime is not started");
        }
        if (parent == null) {
            return supervisor.spawn(request);
        }
        AgentRequest child = new AgentRequest(
                request.templateId(),
                request.template(),
                request.name(),
                request.workspace(),
                request.sessionId(),
                request.systemPrompt(),
                request.toolNames(),
                request.model(),
                parent.pid(),
                request.namespaceId() != null ? request.namespaceId() : parent.namespaceId(),
                request.capabilities(),
                request.budget(),
                request.forceResume(),
                request.metadata());
        return supervisor.spawn(child);
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
        for (SessionStore store : stores.values()) {
            try {
                store.close();
            } catch (RuntimeException e) {
                log.warn("session store failed to close", e);
            }
        }
        stores.clear();
        jobRegistry.shutdown();
        // The mod loader is released last: it owns the mod classloaders.
        modLoader.close();
        log.info("jarness stopped");
    }

    private SessionStore store(String jdbcUrl) {
        return stores.computeIfAbsent(jdbcUrl, url -> {
            LocalSessionStore store = new LocalSessionStore(url);
            log.info("namespace store open at {}", url);
            return store;
        });
    }

    private NamespaceRegistryImpl buildNamespaces(SessionStore defaultStore) {
        List<CapabilityRequest> defaults = defaultCapabilities();
        ModelProfile defaultModel = config.defaultProvider().isBlank() || config.defaultModel().isBlank()
                ? null
                : ModelProfile.of(new ModelRef(config.defaultProvider(), config.defaultModel()));

        NamespaceMetadata defaultMetadata = new NamespaceMetadata(
                "default", Map.of(), defaultModel, Map.of(), PolicySet.empty(), CapabilitySet.grant("ns.default", defaults));
        Namespace defaultNamespace = new NamespaceImpl(Namespace.DEFAULT_ID,
                new NamespaceSpec(Namespace.DEFAULT_ID, defaultMetadata, defaultStore,
                        blankToNull(config.defaultTemplate()), ""), true);
        NamespaceRegistryImpl registry = new NamespaceRegistryImpl(defaultNamespace);

        ConfigView table = config.raw().table("namespaces");
        for (String id : table.keys()) {
            if (Namespace.DEFAULT_ID.equals(id)) {
                log.warn("config [namespaces.default] is ignored; the default namespace is built from [defaults]");
                continue;
            }
            ConfigView view = table.table(id);
            String storeUrl = view.string("store", config.sessionStoreUrl());
            SessionStore store = store(storeUrl);
            ModelProfile model = view.findString("model").map(text -> ModelProfile.of(ModelRef.parse(text)))
                    .orElse(defaultModel);
            CapabilitySet caps = parseCapabilities("ns." + id, view.stringList("capabilities"), defaults);
            String template = view.string("template", config.defaultTemplate());
            NamespaceMetadata metadata = new NamespaceMetadata(
                    view.string("name", id), Map.of(), model, Map.of(), PolicySet.empty(), caps);
            registry.create(new NamespaceSpec(id, metadata, store, blankToNull(template), ""));
            log.info("namespace {} ready (store {}, template {}, caps {})", id, storeUrl, template, caps.kinds());
        }
        return registry;
    }

    private List<CapabilityRequest> defaultCapabilities() {
        return List.of(
                CapabilityRequest.of(CapKind.FS_READ),
                CapabilityRequest.of(CapKind.FS_WRITE),
                CapabilityRequest.of(CapKind.EXEC),
                CapabilityRequest.of(CapKind.NET),
                CapabilityRequest.of(CapKind.MODEL),
                CapabilityRequest.of(CapKind.SPAWN),
                CapabilityRequest.of(CapKind.EXTEND),
                CapabilityRequest.of(CapKind.IPC));
    }

    private CapabilitySet parseCapabilities(String prefix, List<String> configured, List<CapabilityRequest> defaults) {
        if (configured == null || configured.isEmpty()) {
            return CapabilitySet.grant(prefix, defaults);
        }
        List<CapabilityRequest> requests = new ArrayList<>();
        for (String text : configured) {
            CapKind kind = CapKind.parse(text);
            if (kind == null) {
                log.warn("namespace capability \"{}\" is not recognized; ignoring", text);
                continue;
            }
            if (kind == CapKind.PLATFORM) {
                log.warn("namespace capability \"platform\" is reserved for the super agent; ignoring");
                continue;
            }
            requests.add(CapabilityRequest.of(kind));
        }
        return CapabilitySet.grant(prefix, requests);
    }

    private void warnAboutMissingTemplates() {
        for (Namespace namespace : namespaces.list()) {
            String templateId = namespace.defaultTemplateId();
            if (templateId != null && !templateRegistry.contains(templateId)) {
                log.warn("namespace {} declares default template \"{}\", which is not registered yet; "
                        + "spawn will be refused until a mod registers it", namespace.id(), templateId);
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Preset resolvePreset() {
        String name = presetName != null && !presetName.isBlank() ? presetName : config.preset();
        return PresetLoader.load(layout, name).orElseThrow(() -> {
            List<String> available = PresetLoader.all(layout).stream().map(Preset::name).toList();
            return new IllegalStateException("preset \"" + name + "\" not found; available: " + available
                    + " (looked in " + layout.presetsDir() + " and builtin presets)");
        });
    }
}
