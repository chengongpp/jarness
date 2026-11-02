package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentContext;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentSignal;
import ch.predmet.jarness.api.agent.AgentState;
import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.agent.TurnResult;
import ch.predmet.jarness.api.approval.ApprovalDecision;
import ch.predmet.jarness.api.approval.ApprovalPolicy;
import ch.predmet.jarness.api.approval.ApprovalRequest;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.hook.HookContext;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.event.AgentEvent;
import ch.predmet.jarness.api.hook.event.MessageEvent;
import ch.predmet.jarness.api.hook.event.RequestEvent;
import ch.predmet.jarness.api.hook.event.ResponseEvent;
import ch.predmet.jarness.api.hook.event.SessionEvent;
import ch.predmet.jarness.api.hook.event.ToolCallEvent;
import ch.predmet.jarness.api.hook.event.TurnEvent;
import ch.predmet.jarness.api.kernel.AgentManifest;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.kernel.ExitKind;
import ch.predmet.jarness.api.kernel.ExitStatus;
import ch.predmet.jarness.api.loop.LoopHook;
import ch.predmet.jarness.api.mailbox.Mailbox;
import ch.predmet.jarness.api.mailbox.MailboxMessage;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceMetadata;
import ch.predmet.jarness.api.namespace.ModelProfile;
import ch.predmet.jarness.api.provider.ChatRequest;
import ch.predmet.jarness.api.provider.ChatResponse;
import ch.predmet.jarness.api.provider.ModelInfo;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.provider.Provider;
import ch.predmet.jarness.api.provider.ProviderException;
import ch.predmet.jarness.api.provider.StreamSink;
import ch.predmet.jarness.api.provider.Usage;
import ch.predmet.jarness.api.registry.Registry;
import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.ImageMetadata;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.ResumeDeniedException;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.SessionInit;
import ch.predmet.jarness.api.session.Step;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.api.supervisor.AgentStatus;
import ch.predmet.jarness.api.supervisor.Supervisor;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolForm;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolMode;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;
import ch.predmet.jarness.core.exec.RelayTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The default {@link Agent}: one virtual-thread worker draining a submission queue, each
 * submission running the provider/tool turn loop.
 *
 * <p>Only {@link SupervisorImpl} constructs agents. Every step of the turn loop is observable and
 * rewritable through the hook bus: messages pass {@code MESSAGE_APPENDED}, the outgoing request
 * passes {@code BEFORE_REQUEST} and may be cancelled, responses pass {@code AFTER_RESPONSE}, and
 * each tool call passes {@code BEFORE_TOOL_CALL}/{@code AFTER_TOOL_CALL}. Hook dispatch is
 * defensive: a failing hook is logged and never kills the worker thread.</p>
 *
 * <p>The agent holds a pid, a parent, one namespace, a mutable capability set (revocation takes
 * effect on the next tool call), and a mutable budget (tokens and tool slots are spent as the turn
 * progresses). Stopping the worker kills the subtree and resolves the agent's exit status.</p>
 */
public final class AgentImpl implements Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger AUDIT = LoggerFactory.getLogger("ch.predmet.jarness.audit");

    private record Submitted(Message message, CompletableFuture<TurnResult> future) {
    }

    private final JarnessRuntime runtime;
    private final SupervisorImpl supervisor;
    private final long pid;
    private final long parentPid;
    private final int depth;
    private final Namespace namespace;
    private final AgentManifest manifest;
    private final AtomicReference<CapabilitySet> capabilities;
    private final AtomicReference<Budget> budget;
    private final AgentRequest request;
    private final AgentTemplate template;
    private final ToolContextFactory toolContextFactory;
    private final String id;
    private final String name;
    private final Path workspace;
    private final Session session;
    private final AgentContextImpl ctx;
    private final Logger log;
    private final CompletableFuture<ExitStatus> exit = new CompletableFuture<>();

    private final BlockingQueue<Submitted> queue;
    private final Thread worker;
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.NEW);
    private final MailboxImpl mailbox;
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, String> pendingAsync = new ConcurrentHashMap<>();
    private final AtomicInteger turnCount = new AtomicInteger();
    private final AtomicInteger stepTotal = new AtomicInteger();
    private final AtomicInteger toolCallTotal = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final Set<String> committedTurns = ConcurrentHashMap.newKeySet();

    private volatile boolean stopFlag;
    private volatile boolean suspendFlag;
    private volatile boolean turnCancel;
    private volatile String cancelReason = "";
    private volatile String stopReason = "stopped";
    private volatile boolean pauseRequested;
    private final Object pauseLock = new Object();
    private volatile boolean busy;
    private volatile String lastError;
    private final Instant startedAt = Instant.now();
    private volatile Instant lastActivityAt = startedAt;
    private volatile boolean titleKnown;
    private volatile Registry.Snapshot turnSnapshot;

    /** Lets a provider abandon a long generation when the turn is cancelled. */
    private final StreamSink cancellationSink = new StreamSink() {
        @Override
        public boolean cancelled() {
            return turnCancel || stopFlag;
        }
    };

    AgentImpl(JarnessRuntime runtime, SupervisorImpl supervisor, long pid, Agent parent, int depth,
              Namespace namespace, AgentManifest manifest, CapabilitySet capabilities, Budget budget,
              AgentRequest request, AgentTemplate template, ToolContextFactory toolContextFactory,
              AgentImpl forkFrom) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.pid = pid;
        this.parentPid = parent == null ? SupervisorImpl.ROOT_PID : parent.pid();
        this.depth = depth;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.capabilities = new AtomicReference<>(capabilities == null ? CapabilitySet.empty() : capabilities);
        this.budget = new AtomicReference<>(budget == null ? Budget.unlimited() : budget);
        this.request = Objects.requireNonNull(request, "request");
        this.template = Objects.requireNonNull(template, "template");
        this.toolContextFactory = Objects.requireNonNull(toolContextFactory, "toolContextFactory");
        this.id = "agent-" + pid;
        this.name = request.name() != null && !request.name().isBlank()
                ? request.name()
                : manifest.name() != null && !manifest.name().isBlank()
                ? manifest.name()
                : template.id() + "-" + pid;
        this.log = LoggerFactory.getLogger("ch.predmet.jarness.agent." + id);
        this.mailbox = new MailboxImpl(runtime.config().raw().integer("mailbox.capacity", 256));
        this.queue = new ArrayBlockingQueue<>(Math.max(1,
                runtime.config().raw().integer("mailbox.submission_capacity", 256)));

        Path ws = namespace.metadata().cwd();
        if (ws.equals(runtime.layout().baseDir())) {
            throw new IllegalArgumentException("namespace cwd must not be the runtime base directory");
        }
        try {
            Files.createDirectories(ws);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create namespace cwd " + ws, e);
        }
        this.workspace = ws;

        Optional<ImageMetadata> prior = resumeImage();
        ResumeCheck resume = checkResume(prior);
        if (!resume.problems().isEmpty() && !request.forceResume()) {
            throw new ResumeDeniedException(request.sessionId(), resume.problems());
        }

        Map<String, Object> sessionMetadata = new LinkedHashMap<>(request.metadata());
        sessionMetadata.put("namespace", namespace.id());

        if (forkFrom != null) {
            Session parentSession = forkFrom.session();
            this.session = namespace.store().create(new SessionInit(request.sessionId(), id,
                    parentSession.title(), workspace, template.id(), initialModel(), sessionMetadata));
            for (Message message : parentSession.messages()) {
                this.session.append(message.withId(UUID.randomUUID().toString()));
            }
            log.info("forked image from {}: {} message(s) copied", forkFrom.id(), parentSession.messages().size());
        } else {
            this.session = namespace.store().loadOrCreate(new SessionInit(
                    request.sessionId(), id, null, workspace, template.id(), initialModel(), sessionMetadata));
        }

        if (session.workspace() == null || !session.workspace().toAbsolutePath().normalize().equals(ws)) {
            session.rebindWorkspace(ws);
        }

        if (prior.isPresent() && request.budget() == null) {
            this.budget.set(this.budget.get().narrower(prior.get().budget()));
        }

        this.ctx = new AgentContextImpl(this, runtime);
        List<String> requestedTools = request.toolNames().isEmpty()
                ? template.toolNames() : request.toolNames();
        if (resume.problems().isEmpty()) {
            ctx.baseTools(requestedTools);
        } else {
            List<String> available = new ArrayList<>(requestedTools.size());
            for (String name : requestedTools) {
                if (runtime.tools().contains(name)) {
                    available.add(name);
                }
            }
            ctx.baseTools(available);
            ctx.systemPromptSection("image-degraded", -90, () -> degradedPrompt(resume));
            log.warn("resumed image {} with degraded tools: {}", session.id(), resume.missingTools());
        }
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ctx.systemPromptSection("request-override", -100, request::systemPrompt);
        }

        state.set(AgentState.IDLE);
        saveImageQuietly();
        emit(HookPoint.SESSION_CREATED, new SessionEvent(this, session));
        this.worker = Thread.ofVirtual().name("jarness-" + id).unstarted(this::runLoop);
        this.worker.start();
    }

    /** A resume check: the prior image, what no longer matches, and the resulting plan. */
    private record ResumeCheck(ImageMetadata image, List<String> problems, List<String> missingTools,
                               boolean forced) {
        static ResumeCheck none() {
            return new ResumeCheck(null, List.of(), List.of(), false);
        }
    }

    /** @return the prior image of the session being resumed, if any. */
    private Optional<ImageMetadata> resumeImage() {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return namespace.store().image(sessionId);
    }

    /**
     * Compare the image's dependency manifest with the live runtime.
     *
     * @param prior the prior image, if any
     * @return the check result
     */
    private ResumeCheck checkResume(Optional<ImageMetadata> prior) {
        if (prior.isEmpty()) {
            return ResumeCheck.none();
        }
        ImageMetadata image = prior.get();
        List<String> problems = new ArrayList<>();
        List<String> missingTools = new ArrayList<>();
        if (image.schemaVersion() != ImageMetadata.SCHEMA_VERSION) {
            problems.add("image schema " + image.schemaVersion() + " != " + ImageMetadata.SCHEMA_VERSION);
        }
        Map<String, Object> deps = image.deps();
        Object rawTemplate = deps.get("template");
        if (rawTemplate instanceof Map<?, ?> map) {
            String templateId = text(map.get("id"));
            String version = text(map.get("version"));
            if (!template.id().equals(templateId)) {
                problems.add("template changed from \"" + templateId + "\" to \"" + template.id() + "\"");
            } else if (!version.isBlank() && !version.equals(template.version())) {
                problems.add("template version changed from " + version + " to " + template.version());
            }
        } else {
            problems.add("image has no template dependency");
        }
        Set<String> loadedMods = new LinkedHashSet<>();
        for (var mod : runtime.modLoader().loaded()) {
            loadedMods.add(mod.descriptor().id());
        }
        if (deps.get("mods") instanceof List<?> mods) {
            for (Object item : mods) {
                if (item instanceof Map<?, ?> map) {
                    String modId = text(map.get("id"));
                    if (!modId.isBlank() && !loadedMods.contains(modId)) {
                        problems.add("mod not loaded: " + modId);
                    }
                }
            }
        }
        if (deps.get("pending") instanceof List<?> pending && !pending.isEmpty()) {
            problems.add("asynchronous calls were still pending at the checkpoint");
        }
        if (deps.get("tools") instanceof List<?> tools) {
            for (Object item : tools) {
                String name = text(item);
                if (!name.isBlank() && !runtime.tools().contains(name)) {
                    missingTools.add(name);
                }
            }
            if (!missingTools.isEmpty()) {
                problems.add("tools no longer registered: " + missingTools);
            }
        }
        return new ResumeCheck(image, List.copyOf(problems), List.copyOf(missingTools),
                request.forceResume() && !problems.isEmpty());
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String degradedPrompt(ResumeCheck resume) {
        StringBuilder sb = new StringBuilder("# Degraded image\n");
        sb.append("This image was loaded with --force-resume because its dependencies no longer match ")
                .append("the running runtime. Only the tools listed below are available; tell the user ")
                .append("when they ask for something that used to exist.\n");
        if (!resume.missingTools().isEmpty()) {
            sb.append("\nUnavailable tools: ").append(String.join(", ", resume.missingTools())).append('\n');
        }
        if (!resume.problems().isEmpty()) {
            sb.append("\nDependency mismatches: ").append(String.join("; ", resume.problems())).append('\n');
        }
        return sb.toString();
    }

    /** @return the model selected by the namespace's profile, or {@code null}. */
    private ModelRef profileModel() {
        NamespaceMetadata metadata = namespace.metadata();
        ModelProfile profile = metadata.profile(manifest.modelProfileId());
        return profile == null ? null : profile.model();
    }

    /** @return a best-effort model for the session record, or {@code null} when none resolves yet. */
    private ModelRef initialModel() {
        ModelRef candidate = request.model() != null ? request.model() : profileModel();
        if (candidate == null) {
            candidate = runtime.providers().defaultModel().orElse(null);
        }
        if (candidate == null) {
            return null;
        }
        try {
            return runtime.providers().resolve(candidate);
        } catch (RuntimeException e) {
            log.warn("could not pre-resolve model {}: {}", candidate, e.getMessage());
            return candidate;
        }
    }

    /**
     * Resolve the model for one request: request override, namespace profile, session binding, then
     * the runtime default, completed through the provider registry.
     */
    private ModelRef resolveModel() {
        ModelRef candidate = request.model() != null ? request.model()
                : profileModel() != null ? profileModel()
                : session.model() != null ? session.model()
                : runtime.providers().defaultModel().orElse(null);
        if (candidate == null) {
            throw new IllegalStateException(
                    "no model configured for agent " + id + ": set a namespace model profile, a default model, "
                            + "or pass one in the request");
        }
        return runtime.providers().resolve(candidate);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public long parentPid() {
        return parentPid;
    }

    @Override
    public int depth() {
        return depth;
    }

    @Override
    public Namespace namespace() {
        return namespace;
    }

    @Override
    public AgentManifest manifest() {
        return manifest;
    }

    @Override
    public CapabilitySet capabilities() {
        return capabilities.get();
    }

    @Override
    public Budget budget() {
        return budget.get();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentTemplate template() {
        return template;
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public AgentState state() {
        return state.get();
    }

    @Override
    public Session session() {
        return session;
    }

    @Override
    public Supervisor supervisor() {
        return supervisor;
    }

    @Override
    public Mailbox mailbox() {
        return mailbox;
    }

    @Override
    public AgentContext ctx() {
        return ctx;
    }

    @Override
    public Logger log() {
        return log;
    }

    @Override
    public CompletableFuture<TurnResult> submit(String input) {
        return submit(Message.user(input));
    }

    @Override
    public CompletableFuture<TurnResult> submit(Message input) {
        Objects.requireNonNull(input, "input");
        CompletableFuture<TurnResult> future = new CompletableFuture<>();
        AgentState current = state.get();
        if (stopFlag || current == AgentState.STOPPED || current == AgentState.FAILED) {
            future.completeExceptionally(
                    new IllegalStateException("agent " + id + " is not accepting work (" + current + ")"));
            return future;
        }
        if (!queue.offer(new Submitted(input, future))) {
            future.completeExceptionally(new IllegalStateException("agent " + id
                    + " submission queue is full (capacity " + queue.remainingCapacity() + queue.size() + ")"));
            return future;
        }
        touch();
        return future;
    }

    @Override
    public boolean deliver(MailboxMessage message) {
        AgentState current = state.get();
        if (stopFlag || current == AgentState.STOPPED || current == AgentState.FAILED
                || current == AgentState.SUSPENDED || current == AgentState.DEACTIVATING) {
            return false;
        }
        return mailbox.offer(message);
    }

    @Override
    public void signal(AgentSignal signal) {
        if (signal == null) {
            return;
        }
        emit(HookPoint.AGENT_SIGNAL, new AgentEvent(this, "signal", signal));
        switch (signal) {
            case PAUSE -> pauseRequested = true;
            case RESUME -> {
                pauseRequested = false;
                synchronized (pauseLock) {
                    pauseLock.notifyAll();
                }
            }
            case INTERRUPT -> {
                cancelReason = "interrupted";
                turnCancel = true;
                pauseRequested = false;
                synchronized (pauseLock) {
                    pauseLock.notifyAll();
                }
            }
            case STOP -> initiateStop("stop signal");
            default -> {
            }
        }
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public int turnCount() {
        return turnCount.get();
    }

    @Override
    public boolean awaitIdle(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (!busy && queue.isEmpty() && mailbox.size() == 0 && pendingAsync.isEmpty()) {
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, remaining / 1_000_000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Override
    public Optional<ExitStatus> awaitExit(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            return Optional.of(exit.get(Math.max(0L, timeout.toMillis()), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.util.concurrent.ExecutionException e) {
            return Optional.of(new ExitStatus(ExitKind.FAILED, String.valueOf(e.getCause())));
        }
    }

    @Override
    public AgentStatus status() {
        return new AgentStatus(id, pid, parentPid, namespace.id(), depth, name, template.id(), state.get(),
                session.id(), workspace, turnCount.get(), stepTotal.get(), toolCallTotal.get(),
                (int) promptTokens.get(), (int) completionTokens.get(), queue.size() + mailbox.size(), startedAt,
                lastActivityAt, lastError);
    }

    @Override
    public void stop(String reason) {
        initiateStop(reason);
    }

    /**
     * Supervisor-facing stop: performs the local shutdown without touching the registry, which the
     * supervisor owns.
     *
     * @param reason short reason recorded on the agent and emitted to hooks
     */
    void markStopped(String reason) {
        initiateStop(reason);
    }

    /**
     * Checkpoint-facing stop: stop the worker and record the node as suspended rather than stopped.
     *
     * @param reason short reason
     */
    void markSuspended(String reason) {
        suspendFlag = true;
        initiateStop(reason);
    }

    /**
     * Remove grants revoked by the supervisor. The next tool call re-reads the set, so revocation
     * takes effect without restarting the agent.
     *
     * @param capIds the grant ids to drop
     */
    void revokeCaps(Set<String> capIds) {
        capabilities.updateAndGet(set -> {
            CapabilitySet next = set;
            for (String capId : capIds) {
                next = next.without(capId);
            }
            return next;
        });
        log.info("revoked {} capability grant(s); {} remain", capIds.size(), capabilities.get().size());
        saveImageQuietly();
    }

    /**
     * Checkpoint this agent's image: snapshot the session and compact the covered turn deltas.
     */
    void checkpoint() {
        if (Thread.currentThread() == worker && busy) {
            throw new IllegalStateException("cannot checkpoint from inside a running turn");
        }
        synchronized (this) {
            namespace.store().checkpoint(session, imageMetadata());
            log.info("checkpointed image of {} at turn {}", id, session.nextTurnIndex() - 1);
        }
    }

    private void initiateStop(String reason) {
        stopReason = reason == null || reason.isBlank() ? "stopped" : reason;
        AgentState current = state.get();
        if (current != AgentState.FAILED && current != AgentState.SUSPENDED) {
            state.compareAndSet(current, AgentState.DEACTIVATING);
        }
        stopFlag = true;
        pauseRequested = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        Thread w = worker;
        if (w != null && Thread.currentThread() != w && w.isAlive()) {
            try {
                w.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------- worker

    private void runLoop() {
        try {
            while (!stopFlag) {
                MailboxMessage message = mailbox.poll(java.time.Duration.ZERO).orElse(null);
                if (message != null) {
                    handleMailbox(message);
                    continue;
                }
                Submitted sub;
                try {
                    sub = queue.poll(100L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (sub != null) {
                    runTurn(sub);
                }
            }
        } catch (Throwable t) {
            log.error("agent worker died", t);
            lastError = String.valueOf(t.getMessage());
            state.set(AgentState.FAILED);
        } finally {
            drainQueue();
            finishShutdown();
        }
    }

    private void handleMailbox(MailboxMessage message) {
        switch (message.kind()) {
            case CONTROL -> {
                if (message.payload() instanceof AgentSignal signal) {
                    signal(signal);
                }
            }
            case DATA -> submit(String.valueOf(message.payload()));
            case ASYNC_RESULT -> {
                if (message.payload() instanceof MailboxMessage.AsyncResult result) {
                    String text = "# Async tool result\n"
                            + "callId: " + result.callId() + "\n"
                            + "tool: " + result.tool() + "\n"
                            + "ok: " + result.ok() + "\n\n"
                            + result.text();
                    submit(Message.user(text));
                }
            }
        }
    }

    private void drainQueue() {
        Submitted sub;
        while ((sub = queue.poll()) != null) {
            sub.future().complete(new TurnResult(id, session.id(), "", -1, TurnState.CANCELLED, "",
                    stopReason, 0, 0, 0, Duration.ZERO));
        }
    }

    private void finishShutdown() {
        if (state.get() != AgentState.FAILED) {
            state.set(suspendFlag ? AgentState.SUSPENDED : AgentState.STOPPED);
            lastError = null;
        }
        busy = false;
        touch();
        mailbox.close();
        asyncExecutor.shutdownNow();
        saveImageQuietly();
        pendingAsync.clear();
        ctx.closeHooks();
        emit(HookPoint.AGENT_STOPPED, AgentEvent.of(this, stopReason));
        emit(HookPoint.SESSION_ENDED, new SessionEvent(this, session));
        ExitKind kind = state.get() == AgentState.FAILED ? ExitKind.FAILED : exitKind(stopReason);
        ExitStatus status = new ExitStatus(kind, stopReason);
        exit.complete(status);
        supervisor.agentTerminated(this, stopReason, status);
    }

    private static ExitKind exitKind(String reason) {
        String value = reason == null ? "" : reason.toLowerCase();
        if (value.contains("fail")) {
            return ExitKind.FAILED;
        }
        if (value.contains("kill") || value.contains("stop") || value.contains("shutdown")
                || value.contains("parent")) {
            return ExitKind.KILLED;
        }
        return ExitKind.NORMAL;
    }

    // ------------------------------------------------------------- turn loop

    private synchronized void runTurn(Submitted sub) {
        AgentState current = state.get();
        if (current == AgentState.STOPPED || current == AgentState.FAILED) {
            sub.future().completeExceptionally(
                    new IllegalStateException("agent " + id + " is " + current));
            return;
        }
        busy = true;
        state.set(AgentState.RUNNING);
        turnSnapshot = runtime.registry().snapshot();
        turnCancel = false;
        cancelReason = "";
        touch();
        Turn turn = null;
        long turnStart = System.nanoTime();
        try {
            String inputText = sub.message().text();
            turn = session.startTurn(inputText);
            final Turn hookTurn = turn;
            appendMessage(sub.message());
            turnCount.incrementAndGet();
            if (!titleKnown && turn.index() == 0) {
                session.rename(inputText.length() <= 60 ? inputText : inputText.substring(0, 60));
            }
            titleKnown = true;

            ModelRef model;
            try {
                model = resolveModel();
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                turn.finish(TurnState.FAILED, "", e.getMessage());
                commitTurn(turn);
                return;
            }
            if (!model.equals(session.model())) {
                session.setModel(model);
            }

            emit(HookPoint.TURN_START, TurnEvent.of(this, session, turn));
            Turn startedTurn = turn;
            loopHook(hook -> hook.beforeTurn(this, startedTurn));

            int maxSteps = template.maxSteps() > 0 ? template.maxSteps() : runtime.config().maxSteps();
            String lastText = "";
            for (int step = 0; step < maxSteps; step++) {
                String cancel = pendingCancelReason();
                if (cancel != null) {
                    turn.finish(TurnState.CANCELLED, lastText, cancel);
                    commitTurn(turn);
                    break;
                }
                String budgetStop = budgetStopReason();
                if (budgetStop != null) {
                    turn.finish(TurnState.FAILED, lastText, budgetStop);
                    commitTurn(turn);
                    lastError = budgetStop;
                    break;
                }
                if (!awaitIfPaused()) {
                    String reason = pendingCancelReason();
                    turn.finish(TurnState.CANCELLED, lastText, reason == null ? "cancelled" : reason);
                    commitTurn(turn);
                    break;
                }

                emit(HookPoint.STEP_START, TurnEvent.of(this, session, turn, step));
                int currentStep = step;
                loopHook(hook -> hook.beforeStep(this, hookTurn, currentStep));
                Instant stepStart = Instant.now();

                String system = PromptAssembler.systemPrompt(this, runtime);
                List<ToolSpec> tools = resolveToolSpecs();
                List<Message> messages = PromptAssembler.messages(this, session);
                int compactAfter = runtime.config().raw().integer("limits.compact_after_messages", 200);
                int compactKeep = runtime.config().raw().integer("limits.compact_keep_messages", 80);
                if (messages.size() > compactAfter) {
                    messages = ContextCompactor.compact(messages, compactKeep);
                }
                int maxTokens = advertisedMaxTokens(model);
                if (maxTokens <= 0) {
                    maxTokens = runtime.config().maxTokens();
                }
                Double temperature = template.temperature() != null
                        ? template.temperature()
                        : Double.valueOf(modelTemperature());

                RequestEvent event = new RequestEvent(this, session, turn, step, system, messages, tools,
                        model, maxTokens, temperature);
                HookContext reqCtx = emit(HookPoint.BEFORE_REQUEST, event);
                if (event.cancelled() || (reqCtx != null && reqCtx.cancelled())) {
                    String reason = event.cancelled()
                            ? event.cancelReason()
                            : reqCtx.cancelReason();
                    turn.addStep(new Step(step, stepStart, Instant.now(), "cancelled", 0, 0, 0));
                    turn.finish(TurnState.FAILED, "", reason);
                    commitTurn(turn);
                    lastError = reason;
                    break;
                }

                ChatResponse response;
                try {
                    Provider provider = providerFor(event.model());
                    ChatRequest chatRequest = new ChatRequest(event.model(), event.system(), event.messages(),
                            event.tools(), event.maxTokens(), event.temperature(), List.of(), Map.of());
                    response = completeWithRetry(provider, chatRequest);
                } catch (ProviderException e) {
                    log.warn("provider call failed: {}", e.getMessage());
                    turn.addStep(new Step(step, stepStart, Instant.now(), "error", 0, 0, 0));
                    turn.finish(TurnState.FAILED, "", e.getMessage());
                    commitTurn(turn);
                    lastError = e.getMessage();
                    break;
                }

                ResponseEvent responseEvent = new ResponseEvent(this, session, turn, step, response);
                emit(HookPoint.AFTER_RESPONSE, responseEvent);
                response = responseEvent.response();

                Message assistant = response.message();
                if (assistant != null) {
                    appendMessage(assistant);
                }
                Usage usage = response.usage();
                budget.updateAndGet(b -> b.consumeTokens(usage.total()));
                int toolCallCount = assistant == null ? 0 : assistant.toolCalls().size();
                turn.addStep(new Step(step, stepStart, Instant.now(), response.finishReason(),
                        usage.promptTokens(), usage.completionTokens(), toolCallCount));
                int completedStep = step;
                loopHook(hook -> hook.afterStep(this, hookTurn, completedStep));
                stepTotal.incrementAndGet();
                promptTokens.addAndGet(usage.promptTokens());
                completionTokens.addAndGet(usage.completionTokens());
                commitTurn(turn);
                lastText = response.text();
                touch();

                if (response.hasToolCalls()) {
                    for (ContentBlock.ToolCall call : assistant.toolCalls()) {
                        executeToolCall(turn, call);
                    }
                    continue;
                }
                turn.finish(TurnState.COMPLETED, response.text(), null);
                commitTurn(turn);
                break;
            }

            if (turn.state() == TurnState.RUNNING) {
                turn.finish(TurnState.MAX_STEPS, lastText, "step budget exhausted");
                commitTurn(turn);
            }
        } catch (Throwable t) {
            log.error("turn failed unexpectedly", t);
            String failure = String.valueOf(t.getMessage());
            lastError = failure;
            if (t instanceof CommitException) {
                // The in-memory session may contain messages that never became durable. Stop this
                // writer instead of letting a later turn accidentally carry them into another commit.
                stopReason = failure;
                stopFlag = true;
                state.set(AgentState.FAILED);
            }
            if (turn != null) {
                boolean wasRunning = turn.state() == TurnState.RUNNING;
                turn.finish(TurnState.FAILED, turn.output(), failure);
                if (wasRunning) {
                    try {
                        commitTurn(turn);
                    } catch (Throwable commitFailure) {
                        lastError = String.valueOf(commitFailure.getMessage());
                        turn.finish(TurnState.FAILED, turn.output(), lastError);
                        stopReason = lastError;
                        stopFlag = true;
                        state.set(AgentState.FAILED);
                    }
                }
            }
        } finally {
            if (turn != null) {
                emit(HookPoint.TURN_END, TurnEvent.of(this, session, turn));
                Turn finalTurn = turn;
                loopHook(hook -> hook.afterTurn(this, finalTurn));
            }
            busy = false;
            AgentState s = state.get();
            if (s == AgentState.RUNNING || s == AgentState.WAITING) {
                state.set(AgentState.IDLE);
            }
            touch();
            sub.future().complete(buildResult(turn, turnStart));
        }
    }

    /**
     * Complete with bounded exponential backoff for retryable provider failures (timeouts, 429,
     * 5xx). Tool timeouts are not retried; they are returned as observations.
     *
     * @param provider the provider
     * @param request  the request
     * @return the response
     * @throws ProviderException when retries are exhausted or the failure is not retryable
     */
    private ChatResponse completeWithRetry(Provider provider, ChatRequest request) {
        int retries = Math.max(0, runtime.config().raw().integer("provider.retries", 2));
        int base = Math.max(1, runtime.config().raw().integer("provider.backoff_ms", 500));
        int cap = Math.max(base, runtime.config().raw().integer("provider.backoff_max_ms", 8000));
        for (int attempt = 0; ; attempt++) {
            try {
                return provider.complete(request, cancellationSink);
            } catch (ProviderException e) {
                if (!e.retryable() || attempt >= retries || stopFlag || turnCancel) {
                    throw e;
                }
                long delay = Math.min(cap, base * (1L << Math.min(attempt, 5)));
                delay += ThreadLocalRandom.current().nextInt(100);
                log.warn("provider call failed ({}); retrying {}/{} in {} ms",
                        e.getMessage(), attempt + 1, retries, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** @return why the turn should stop on budget grounds, or {@code null}. */
    private String budgetStopReason() {
        Budget current = budget.get();
        if (current.expired()) {
            return "budget exhausted: deadline " + current.deadline() + " passed";
        }
        if (!current.hasTokens()) {
            return "budget exhausted: no tokens left (used " + promptTokens.get() + "+"
                    + completionTokens.get() + ")";
        }
        return null;
    }

    /** @return the model profile temperature, falling back to the runtime default. */
    private double modelTemperature() {
        ModelProfile profile = namespace.metadata().profile(manifest.modelProfileId());
        return profile == null
                ? runtime.config().temperature()
                : profile.doubleParam("temperature", runtime.config().temperature());
    }

    private TurnResult buildResult(Turn turn, long turnStartNanos) {
        Duration duration = Duration.ofNanos(System.nanoTime() - turnStartNanos);
        if (turn == null) {
            return new TurnResult(id, session.id(), "", -1, TurnState.FAILED, "",
                    lastError == null ? "turn did not start" : lastError, 0, 0, 0, duration);
        }
        return new TurnResult(id, session.id(), turn.id(), turn.index(), turn.state(), turn.output(),
                turn.error(), turn.steps().size(), turn.promptTokens(), turn.completionTokens(), duration);
    }

    // ------------------------------------------------------------- tools

    private List<ToolSpec> resolveToolSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        Registry.Snapshot snapshot = turnSnapshot;
        if (snapshot != null) {
            for (String name : ctx.visibleToolNames()) {
                Tool tool = snapshot.tool(name);
                if (tool != null && hasRequiredCapabilities(tool)) {
                    specs.add(tool.spec());
                }
            }
            return specs;
        }
        for (Tool tool : runtime.tools().resolve(ctx.visibleToolNames())) {
            if (hasRequiredCapabilities(tool)) {
                specs.add(tool.spec());
            }
        }
        return specs;
    }

    private void executeToolCall(Turn turn, ContentBlock.ToolCall call) {
        Map<String, Object> args = Map.of();
        String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
        String argumentsError = null;
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, new TypeReference<>() {
            });
            if (parsed != null) {
                args = parsed;
            }
        } catch (Exception e) {
            argumentsError = "invalid JSON arguments for tool \"" + call.name() + "\": " + e.getMessage();
            log.warn("could not parse arguments of tool call \"{}\": {}", call.name(), e.getMessage());
        }

        ToolInvocation invocation = new ToolInvocation(call.callId(), call.name(), args, raw);
        Registry.Snapshot snapshot = turnSnapshot;
        Tool tool = snapshot != null ? snapshot.tool(call.name()) : runtime.tools().find(call.name()).orElse(null);

        ToolCallEvent before = new ToolCallEvent(this, session, turn, tool, invocation);
        emit(HookPoint.BEFORE_TOOL_CALL, before);

        ToolOutcome outcome;
        if (before.skipped()) {
            outcome = ToolOutcome.denied(before.skipReason());
        } else if (argumentsError != null) {
            outcome = ToolOutcome.error(argumentsError);
        } else if (tool == null) {
            outcome = ToolOutcome.error("unknown tool \"" + call.name() + "\"");
        } else if (!ctx.visibleToolNames().contains(tool.name())) {
            outcome = ToolOutcome.denied("tool \"" + tool.name() + "\" is not visible to this agent");
        } else {
            String denied = schemaDenial(tool, before.invocation());
            if (denied == null) {
                denied = capabilityDenial(tool);
            }
            if (denied == null) {
                denied = approvalDenial(tool, before.invocation());
            }
            if (denied == null) {
                denied = budgetToolDenial();
            }
            if (denied != null) {
                outcome = ToolOutcome.denied(denied);
            } else if (tool.mode() == ToolMode.ASYNC) {
                outcome = acceptAsync(tool, before.invocation());
            } else {
                try {
                    budget.updateAndGet(Budget::consumeToolSlot);
                    toolCallTotal.incrementAndGet();
                    ToolContext toolContext = toolContextFactory.create(this,
                            () -> turnCancel || stopFlag, this::currentCancelReason);
                    outcome = invokeTool(tool, before.invocation(), toolContext);
                    if (outcome == null) {
                        outcome = ToolOutcome.error("tool returned no outcome");
                    }
                } catch (ToolException e) {
                    outcome = ToolOutcome.error(e.getMessage());
                } catch (Exception e) {
                    log.warn("tool \"{}\" failed", call.name(), e);
                    outcome = ToolOutcome.error("tool failed: " + e.getMessage());
                }
            }
        }
        outcome = truncate(outcome);
        AUDIT.info("agent={} pid={} tool={} ok={} form={}",
                id, pid, call.name(), outcome.ok(), tool == null ? "unknown" : tool.form());

        ToolCallEvent after = new ToolCallEvent(this, session, turn, tool, before.invocation());
        after.setOutcome(outcome);
        emit(HookPoint.AFTER_TOOL_CALL, after);
        ToolOutcome finalOutcome = after.outcome() != null ? after.outcome() : outcome;

        appendMessage(Message.toolResult(call.callId(), call.name(), finalOutcome.ok(),
                finalOutcome.modelText()));
        touch();
    }

    /** Route RELAY declarations centrally; an API-only mod need not wrap its tool in a core class. */
    private ToolOutcome invokeTool(Tool tool, ToolInvocation invocation, ToolContext context) throws Exception {
        if (tool.form() == ToolForm.RELAY) {
            return RelayTool.dispatch(tool, invocation, context);
        }
        return tool.invoke(invocation, context);
    }

    /**
     * Accept an asynchronous tool call. The current turn records {@code accepted}; the completion is
     * delivered to the mailbox and becomes a later turn.
     *
     * @param tool       the tool
     * @param invocation the call
     * @return the acceptance observation
     */
    private ToolOutcome acceptAsync(Tool tool, ToolInvocation invocation) {
        String callId = invocation.id() == null || invocation.id().isBlank()
                ? UUID.randomUUID().toString() : invocation.id();
        budget.updateAndGet(Budget::consumeToolSlot);
        toolCallTotal.incrementAndGet();
        pendingAsync.put(callId, tool.name());
        asyncExecutor.submit(() -> {
            try {
                ToolContext toolContext = toolContextFactory.create(this,
                        () -> turnCancel || stopFlag, this::currentCancelReason);
                ToolOutcome result = invokeTool(tool, invocation, toolContext);
                ToolOutcome finalResult = result == null ? ToolOutcome.error("tool returned no outcome") : result;
                completeAsync(callId, tool.name(), truncate(finalResult));
            } catch (Throwable t) {
                log.warn("async tool \"{}\" failed", tool.name(), t);
                completeAsync(callId, tool.name(), ToolOutcome.error("tool failed: " + t.getMessage()));
            }
        });
        return ToolOutcome.ok("accepted async call " + callId + " (tool " + tool.name()
                + "); the result will arrive as a later turn");
    }

    /**
     * Deliver with backpressure. Pending state is cleared only after the mailbox accepts the result;
     * an agent shutting down leaves it in the image for the explicit resume/reconciliation path.
     */
    private void completeAsync(String callId, String tool, ToolOutcome result) {
        MailboxMessage.AsyncResult payload = new MailboxMessage.AsyncResult(
                callId, tool, result.ok(), result.modelText());
        boolean delivered = false;
        try {
            while (!stopFlag) {
                if (mailbox.offer(MailboxMessage.asyncResult(pid, payload))) {
                    delivered = true;
                    break;
                }
                Thread.sleep(25L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (delivered) {
            pendingAsync.remove(callId);
        } else {
            log.warn("async result {}/{} was not delivered because the agent is stopping; "
                    + "its pending marker remains in the saved image", callId, tool);
        }
    }

    /** @return a denial reason when required tool parameters are missing, else {@code null}. */
    private String schemaDenial(Tool tool, ToolInvocation invocation) {
        for (Map.Entry<String, ch.predmet.jarness.api.tool.ToolParameter> entry : tool.spec().parameters().entrySet()) {
            if (entry.getValue().required() && invocation.arguments().get(entry.getKey()) == null) {
                return "missing required parameter \"" + entry.getKey() + "\" for tool \"" + tool.name() + "\"";
            }
        }
        return null;
    }

    /** @return a denial reason when the agent lacks a capability the tool needs, else {@code null}. */
    private String capabilityDenial(Tool tool) {
        Set<CapKind> required = tool.requiredCaps();
        if (required.isEmpty()) {
            return null;
        }
        CapabilitySet held = capabilities.get();
        for (CapKind kind : required) {
            if (!held.containsKind(kind)) {
                return "capability denied: tool \"" + tool.name() + "\" requires " + kind.id();
            }
        }
        return null;
    }

    /** @return whether every capability required by the tool is currently held. */
    private boolean hasRequiredCapabilities(Tool tool) {
        CapabilitySet held = capabilities.get();
        for (CapKind kind : tool.requiredCaps()) {
            if (!held.containsKind(kind)) {
                return false;
            }
        }
        return true;
    }

    /** @return a denial reason from the approval policy, or {@code null} to run the tool. */
    private String approvalDenial(Tool tool, ToolInvocation invocation) {
        if (!hasSideEffects(tool)) {
            return null;
        }
        ApprovalPolicy policy = ApprovalPolicy.parse(
                namespace.metadata().policies().string("approval",
                        runtime.config().raw().string("approval.policy", "allow")),
                ApprovalPolicy.ALLOW);
        switch (policy) {
            case ALLOW -> {
                return null;
            }
            case NEVER -> {
                if (allowlisted(tool.name())) {
                    return null;
                }
                return "blocked by approval policy: never (tool " + tool.name() + " is not allow-listed)";
            }
            case ASK -> {
                int seconds = runtime.config().raw().integer("approval.timeout_seconds", 300);
                ApprovalDecision decision = runtime.approvals().ask(new ApprovalRequest(id, namespace.id(),
                        tool.name(), invocation.arguments(), tool.spec().description()),
                        Duration.ofSeconds(Math.max(1, seconds)));
                return decision == ApprovalDecision.ALLOW ? null
                        : "approval denied for tool " + tool.name();
            }
            default -> {
                return "unsupported approval policy";
            }
        }
    }

    private boolean hasSideEffects(Tool tool) {
        return !tool.spec().readOnly();
    }

    private boolean allowlisted(String toolName) {
        Object value = namespace.metadata().policies().values().get("allow");
        if (value instanceof java.util.Collection<?> allowed) {
            for (Object entry : allowed) {
                if (toolName.equals(String.valueOf(entry))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return a denial reason when the budget forbids another tool call, else {@code null}. */
    private String budgetToolDenial() {
        Budget current = budget.get();
        if (current.toolSlots() == 0) {
            return "budget exhausted: no tool slots left";
        }
        return current.expired() ? "budget exhausted: deadline passed" : null;
    }

    private ToolOutcome truncate(ToolOutcome outcome) {
        int max = runtime.config().toolOutputMaxChars();
        if (max <= 0 || outcome.text().length() <= max) {
            return outcome;
        }
        return new ToolOutcome(outcome.ok(), outcome.text().substring(0, max) + "\n[truncated]",
                outcome.structured(), outcome.error());
    }

    // ------------------------------------------------------------- helpers

    /** Resolve a model provider from the pinned turn snapshot, not a concurrently changed registry. */
    private Provider providerFor(ModelRef model) {
        Registry.Snapshot snapshot = turnSnapshot;
        if (snapshot != null) {
            for (Provider provider : snapshot.providers()) {
                if (provider.id().equals(model.providerId())) {
                    return provider;
                }
            }
            throw new IllegalArgumentException("provider " + model.providerId()
                    + " is not present in turn registry snapshot " + snapshot.version());
        }
        return runtime.providers().providerFor(model);
    }

    private int advertisedMaxTokens(ModelRef model) {
        try {
            Provider provider = providerFor(model);
            for (ModelInfo info : provider.models()) {
                if (info.id().equals(model.modelId())) {
                    return info.defaultMaxTokens();
                }
            }
        } catch (RuntimeException e) {
            log.debug("could not look up advertised max tokens for {}: {}", model, e.getMessage());
        }
        return 0;
    }

    /**
     * Park while paused. The worker wakes on resume/stop/interrupt, and polls the flag at most
     * every 50 ms as a safety net against a missed notification.
     *
     * @return whether the turn may continue; {@code false} means a stop/interrupt arrived
     */
    private boolean awaitIfPaused() {
        if (!pauseRequested) {
            return true;
        }
        state.set(AgentState.WAITING);
        touch();
        synchronized (pauseLock) {
            while (pauseRequested && !stopFlag && !turnCancel) {
                try {
                    pauseLock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (state.get() == AgentState.WAITING) {
            state.set(AgentState.RUNNING);
            touch();
        }
        return !stopFlag && !turnCancel;
    }

    /** @return why the turn should cancel, or {@code null} when it should run on. */
    private String pendingCancelReason() {
        if (stopFlag) {
            return stopReason;
        }
        if (turnCancel) {
            return cancelReason.isEmpty() ? "interrupted" : cancelReason;
        }
        return null;
    }

    private String currentCancelReason() {
        String reason = pendingCancelReason();
        return reason == null ? "" : reason;
    }

    /**
     * Append a message to the session through {@code MESSAGE_APPENDED}; a hook may rewrite or drop
     * it.
     *
     * @return the appended message, or {@code null} when a hook dropped it
     */
    private Message appendMessage(Message message) {
        try {
            MessageEvent event = new MessageEvent(this, session, message);
            emit(HookPoint.MESSAGE_APPENDED, event);
            if (event.dropped()) {
                return null;
            }
            Message accepted = event.message();
            session.append(accepted);
            return accepted;
        } catch (Throwable t) {
            log.warn("message hook failed; appending the original message", t);
            try {
                session.append(message);
            } catch (Throwable t2) {
                log.error("could not append message to session {}", session.id(), t2);
            }
            return message;
        }
    }

    /**
     * Commit a terminal turn exactly once. Running turns stay in memory until they commit.
     *
     * @param turn the turn
     */
    private void commitTurn(Turn turn) {
        if (turn == null || turn.state() == TurnState.RUNNING || committedTurns.contains(turn.id())) {
            return;
        }
        try {
            session.commitTurn(turn, imageMetadata());
        } catch (Throwable t) {
            throw new CommitException("could not commit turn " + turn.id() + " for session "
                    + session.id() + ": " + t.getMessage(), t);
        }
        committedTurns.add(turn.id());
        int every = runtime.config().raw().integer("session.checkpoint_every", 0);
        if (every > 0 && turn.index() > 0 && turn.index() % every == 0) {
            try {
                namespace.store().checkpoint(session, imageMetadata());
            } catch (Throwable t) {
                throw new CommitException("turn " + turn.id() + " committed, but its checkpoint failed: "
                        + t.getMessage(), t);
            }
        }
    }

    /** Fatal for the current writer: continuing could mix undurable in-memory messages with a later turn. */
    private static final class CommitException extends RuntimeException {
        private CommitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** @return the current image metadata: dependencies, budget, capabilities, state. */
    private ImageMetadata imageMetadata() {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("schema", ImageMetadata.SCHEMA_VERSION);
        deps.put("template", Map.of("id", template.id(), "version", template.version()));
        deps.put("loop", manifest.loopRef());
        deps.put("namespace", namespace.id());
        List<Map<String, Object>> mods = new ArrayList<>();
        for (var mod : runtime.modLoader().loaded()) {
            mods.add(Map.of("id", mod.descriptor().id(), "version", mod.descriptor().version()));
        }
        deps.put("mods", mods);
        deps.put("tools", ctx.visibleToolNames());
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map.Entry<String, String> entry : pendingAsync.entrySet()) {
            pending.add(Map.of("callId", entry.getKey(), "tool", entry.getValue()));
        }
        deps.put("pending", pending);
        AgentState snapshot = state.get() == AgentState.RUNNING ? AgentState.IDLE : state.get();
        return new ImageMetadata(id, session.id(), namespace.id(), ImageMetadata.SCHEMA_VERSION, -1,
                deps, budget.get(), capabilities.get(), snapshot.name(), Instant.now());
    }

    private void saveImageQuietly() {
        try {
            namespace.store().saveImage(imageMetadata());
        } catch (Throwable t) {
            log.warn("could not persist image metadata for {}", id, t);
        }
    }

    /** Dispatch a hook, defensively: a throwing bus or payload never kills the worker. */
    private HookContext emit(HookPoint point, Object payload) {
        try {
            return runtime.hooks().emit(point, payload);
        } catch (Throwable t) {
            log.warn("hook dispatch at {} failed", point, t);
            return null;
        }
    }

    private void loopHook(java.util.function.Consumer<LoopHook> invocation) {
        Registry.Snapshot snapshot = turnSnapshot;
        List<LoopHook> hooks = snapshot == null ? runtime.loopHooks().all() : snapshot.loopHooks();
        for (LoopHook hook : hooks) {
            try {
                invocation.accept(hook);
            } catch (Throwable t) {
                log.warn("loop hook {} failed", hook.name(), t);
            }
        }
    }

    private void touch() {
        lastActivityAt = Instant.now();
    }
}
