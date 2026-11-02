package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.approval.ApprovalRegistry;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.CapabilityRequest;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.JarnessConfig;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.exec.ExecutionRegistry;
import ch.predmet.jarness.api.hook.HookBus;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.instrument.InstrumentRegistry;
import ch.predmet.jarness.api.ipc.IpcRegistry;
import ch.predmet.jarness.api.job.JobRegistry;
import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.api.mod.ModLoader;
import ch.predmet.jarness.api.loop.LoopHookRegistry;
import ch.predmet.jarness.api.observe.Observation;
import ch.predmet.jarness.api.namespace.ModelProfile;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceMetadata;
import ch.predmet.jarness.api.namespace.NamespaceRegistry;
import ch.predmet.jarness.api.namespace.NamespaceSpec;
import ch.predmet.jarness.api.namespace.PolicySet;
import ch.predmet.jarness.api.preset.Preset;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.provider.ProviderRegistry;
import ch.predmet.jarness.api.registry.Registry;
import ch.predmet.jarness.api.session.SessionStore;
import ch.predmet.jarness.api.supervisor.Supervisor;
import ch.predmet.jarness.api.tool.ToolRegistry;
import ch.predmet.jarness.core.agent.AgentTemplateRegistryImpl;
import ch.predmet.jarness.core.approval.ApprovalRegistryImpl;
import ch.predmet.jarness.core.agent.SupervisorImpl;
import ch.predmet.jarness.core.config.ConfigLoader;
import ch.predmet.jarness.core.exec.ExecutionRegistryImpl;
import ch.predmet.jarness.core.exec.LocalExecutionProvider;
import ch.predmet.jarness.core.exec.RemoteExecutorServer;
import ch.predmet.jarness.core.ipc.IpcRegistryImpl;
import ch.predmet.jarness.core.config.PresetLoader;
import ch.predmet.jarness.core.hook.HookBusImpl;
import ch.predmet.jarness.core.job.JobRegistryImpl;
import ch.predmet.jarness.core.mod.ModLoaderImpl;
import ch.predmet.jarness.core.namespace.NamespaceImpl;
import ch.predmet.jarness.core.namespace.NamespaceRegistryImpl;
import ch.predmet.jarness.core.loop.LoopHookRegistryImpl;
import ch.predmet.jarness.core.observe.ObservationImpl;
import ch.predmet.jarness.core.provider.ProviderRegistryImpl;
import ch.predmet.jarness.core.registry.RegistryImpl;
import ch.predmet.jarness.core.session.LocalSessionStore;
import ch.predmet.jarness.core.tool.ToolContextImpl;
import ch.predmet.jarness.core.tool.ToolRegistryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
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
    private final ExecutionRegistryImpl executionRegistry;
    private final ApprovalRegistryImpl approvalRegistry;
    private final IpcRegistryImpl ipcRegistry;
    private final RegistryImpl registry;
    private volatile RemoteExecutorServer remoteExecutorServer;
    private final ObservationImpl observation;
    private final LoopHookRegistryImpl loopHooks;
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
        this.executionRegistry = new ExecutionRegistryImpl();
        this.approvalRegistry = new ApprovalRegistryImpl();
        this.ipcRegistry = new IpcRegistryImpl(layout.imageDb());
        this.registry = new RegistryImpl(() -> this);
        this.stores = new LinkedHashMap<>();
        this.sessionStore = store(config.sessionStoreUrl());
        this.observation = new ObservationImpl(sessionStore, java.time.Duration.ofMillis(
                config.raw().integer("observe.poll_ms", 250)));
        this.loopHooks = new LoopHookRegistryImpl();
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
    public ExecutionRegistry execution() {
        return executionRegistry;
    }

    @Override
    public ApprovalRegistry approvals() {
        return approvalRegistry;
    }

    @Override
    public IpcRegistry ipc() {
        return ipcRegistry;
    }

    @Override
    public Registry registry() {
        return registry;
    }

    @Override
    public Observation observation() {
        return observation;
    }

    @Override
    public LoopHookRegistry loopHooks() {
        return loopHooks;
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
        registry.bump();
        startExecutor();
        startRemoteExecutor();
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
        RemoteExecutorServer remote = remoteExecutorServer;
        if (remote != null) {
            remote.close();
            remoteExecutorServer = null;
        }
        executionRegistry.shutdown();
        ipcRegistry.close();
        observation.close();
        // The mod loader is released last: it owns the mod classloaders.
        modLoader.close();
        log.info("jarness stopped");
    }

    /** Start the local executor when execution is in relay mode. */
    private void startExecutor() {
        String mode = config.raw().string("execution.mode", "relay");
        if ("local".equalsIgnoreCase(mode)) {
            log.info("execution mode is local; relay tools will use the in-daemon handlers");
            return;
        }
        try {
            LocalExecutionProvider provider = LocalExecutionProvider.start(layout.baseDir());
            executionRegistry.register(provider);
            log.info("local executor started ({}; {} tool(s))", provider.binaryPath(), provider.tools().size());
        } catch (Exception e) {
            log.warn("could not start the local executor: {}. RELAY tools will fail until it is built "
                    + "(make executor) or execution.mode is set to local", e.getMessage());
        }
    }

    /** Start the opt-in remote executor listener. */
    private void startRemoteExecutor() {
        if (!config.raw().bool("execution.remote_enabled", false)) {
            return;
        }
        boolean tls = config.raw().bool("execution.remote_tls", false);
        SSLContext tlsContext;
        try {
            tlsContext = tls ? remoteTlsContext() : null;
        } catch (Exception e) {
            log.error("execution.remote_tls is enabled but the keystore could not be loaded: {}. "
                    + "Not starting the remote listener in plaintext", e.getMessage());
            return;
        }
        String token = config.raw().string("execution.remote_token", "");
        if (token.isBlank()) {
            log.warn("execution.remote_enabled is true but execution.remote_token is blank; "
                    + "the remote listener will not start");
            return;
        }
        String host = config.raw().string("execution.remote_host", "127.0.0.1");
        int port = config.raw().integer("execution.remote_port", 0);
        try {
            remoteExecutorServer = RemoteExecutorServer.start(host, port, token, executionRegistry, tlsContext);
            log.info("remote executor listener started on {}:{}{}", host, remoteExecutorServer.port(),
                    tls ? " (TLS)" : " (plaintext)");
        } catch (Exception e) {
            log.warn("could not start the remote executor listener: {}", e.getMessage());
        }
    }

    /**
     * Load the remote listener's PKCS12 keystore.
     *
     * @return the server SSL context
     * @throws Exception when the keystore is missing or unreadable
     */
    private SSLContext remoteTlsContext() throws Exception {
        String keystore = config.raw().string("execution.remote_keystore", "");
        if (keystore.isBlank()) {
            throw new IllegalStateException("execution.remote_keystore is required when remote_tls is true");
        }
        String password = config.raw().string("execution.remote_keystore_password", "changeit");
        Path path = Path.of(keystore);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("keystore not found: " + path);
        }
        char[] secret = password.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, secret);
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, secret);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
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

        Path defaultCwd = namespaceCwd(config.raw().table("defaults"), Namespace.DEFAULT_ID);
        NamespaceMetadata defaultMetadata = new NamespaceMetadata(
                "default", defaultCwd, Map.of(), defaultModel, Map.of(), defaultPolicies(),
                CapabilitySet.grant("ns.default", defaults));
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
            Path cwd = namespaceCwd(view, id);
            NamespaceMetadata metadata = new NamespaceMetadata(
                    view.string("name", id), cwd, Map.of(), model, Map.of(), namespacePolicies(view), caps);
            registry.create(new NamespaceSpec(id, metadata, store, blankToNull(template), ""));
            log.info("namespace {} ready (store {}, cwd {}, template {}, caps {})",
                    id, storeUrl, cwd, template, caps.kinds());
        }
        return registry;
    }

    /**
     * Resolve a namespace's shared working directory.
     *
     * <p>An explicit {@code cwd} in the namespace table wins; relative paths are anchored at the
     * runtime base directory. Otherwise namespaces get {@code <workspace_root>/<id>}.</p>
     */
    private Path namespaceCwd(ConfigView view, String namespaceId) {
        String configured = view.string("cwd", "").trim();
        if (!configured.isEmpty()) {
            Path path = Path.of(configured);
            if (!path.isAbsolute()) {
                path = config.layout().baseDir().resolve(path);
            }
            return path.toAbsolutePath().normalize();
        }
        return config.workspaceRoot().resolve(namespaceId).toAbsolutePath().normalize();
    }

    private PolicySet defaultPolicies() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("approval", config.raw().string("approval.policy", "allow"));
        values.put("allow", config.raw().stringList("approval.allow"));
        return new PolicySet(values);
    }

    private PolicySet namespacePolicies(ConfigView view) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("approval", view.string("approval",
                config.raw().string("approval.policy", "allow")));
        List<String> allow = view.stringList("allow");
        values.put("allow", allow.isEmpty() ? config.raw().stringList("approval.allow") : allow);
        return new PolicySet(values);
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
