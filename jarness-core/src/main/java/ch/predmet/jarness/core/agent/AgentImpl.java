package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentContext;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentSignal;
import ch.predmet.jarness.api.agent.AgentState;
import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.agent.TurnResult;
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
import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.Message;
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
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
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

    private final BlockingQueue<Submitted> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.NEW);
    private final AtomicInteger turnCount = new AtomicInteger();
    private final AtomicInteger stepTotal = new AtomicInteger();
    private final AtomicInteger toolCallTotal = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    private volatile boolean stopFlag;
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

        Path ws = request.workspace() != null
                ? request.workspace()
                : runtime.layout().workspacesDir().resolve("agents").resolve(id);
        ws = ws.toAbsolutePath().normalize();
        if (ws.equals(runtime.layout().baseDir())) {
            throw new IllegalArgumentException("agent workspace must not be the runtime base directory");
        }
        try {
            Files.createDirectories(ws);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create agent workspace " + ws, e);
        }
        this.workspace = ws;

        if (forkFrom != null) {
            Session parentSession = forkFrom.session();
            this.session = namespace.store().create(new SessionInit(request.sessionId(), id,
                    parentSession.title(), workspace, template.id(), initialModel(), request.metadata()));
            for (Message message : parentSession.messages()) {
                this.session.append(message.withId(UUID.randomUUID().toString()));
            }
            log.info("forked image from {}: {} message(s) copied", forkFrom.id(), parentSession.messages().size());
        } else {
            this.session = namespace.store().loadOrCreate(new SessionInit(
                    request.sessionId(), id, null, workspace, template.id(), initialModel(), request.metadata()));
        }

        this.ctx = new AgentContextImpl(this, runtime);
        ctx.baseTools(request.toolNames().isEmpty() ? template.toolNames() : request.toolNames());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ctx.systemPromptSection("request-override", -100, request::systemPrompt);
        }

        emit(HookPoint.SESSION_CREATED, new SessionEvent(this, session));
        state.set(AgentState.IDLE);
        this.worker = Thread.ofVirtual().name("jarness-" + id).unstarted(this::runLoop);
        this.worker.start();
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
        queue.offer(new Submitted(input, future));
        touch();
        return future;
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
            if (!busy && queue.isEmpty()) {
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
                (int) promptTokens.get(), (int) completionTokens.get(), queue.size(), startedAt,
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
    }

    private void initiateStop(String reason) {
        stopReason = reason == null || reason.isBlank() ? "stopped" : reason;
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
                Submitted sub;
                try {
                    sub = queue.poll(100L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (sub == null) {
                    continue;
                }
                runTurn(sub);
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

    private void drainQueue() {
        Submitted sub;
        while ((sub = queue.poll()) != null) {
            sub.future().complete(new TurnResult(id, session.id(), "", -1, TurnState.CANCELLED, "",
                    stopReason, 0, 0, 0, Duration.ZERO));
        }
    }

    private void finishShutdown() {
        if (state.get() != AgentState.FAILED) {
            state.set(AgentState.STOPPED);
            lastError = null;
        }
        busy = false;
        touch();
        ctx.closeHooks();
        emit(HookPoint.AGENT_STOPPED, AgentEvent.of(this, stopReason));
        emit(HookPoint.SESSION_ENDED, new SessionEvent(this, session));
        ExitKind kind = state.get() == AgentState.FAILED ? ExitKind.FAILED : exitKind(stopReason);
        exit.complete(new ExitStatus(kind, stopReason));
        supervisor.agentTerminated(this, stopReason);
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

    private void runTurn(Submitted sub) {
        AgentState current = state.get();
        if (current == AgentState.STOPPED || current == AgentState.FAILED) {
            sub.future().completeExceptionally(
                    new IllegalStateException("agent " + id + " is " + current));
            return;
        }
        busy = true;
        state.set(AgentState.RUNNING);
        turnCancel = false;
        cancelReason = "";
        touch();
        Turn turn = null;
        long turnStart = System.nanoTime();
        boolean sessionDirty = false;
        try {
            Message input = appendMessage(sub.message());
            String inputText = input != null ? input.text() : sub.message().text();
            turn = session.startTurn(inputText);
            turnCount.incrementAndGet();
            if (!titleKnown && turn.index() == 0) {
                session.rename(inputText.length() <= 60 ? inputText : inputText.substring(0, 60));
                sessionDirty = true;
            }
            titleKnown = true;

            ModelRef model;
            try {
                model = resolveModel();
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                turn.finish(TurnState.FAILED, "", e.getMessage());
                saveTurnQuietly(turn);
                return;
            }
            if (!model.equals(session.model())) {
                session.setModel(model);
                sessionDirty = true;
            }

            emit(HookPoint.TURN_START, TurnEvent.of(this, session, turn));

            int maxSteps = template.maxSteps() > 0 ? template.maxSteps() : runtime.config().maxSteps();
            String lastText = "";
            for (int step = 0; step < maxSteps; step++) {
                String cancel = pendingCancelReason();
                if (cancel != null) {
                    turn.finish(TurnState.CANCELLED, lastText, cancel);
                    saveTurnQuietly(turn);
                    break;
                }
                String budgetStop = budgetStopReason();
                if (budgetStop != null) {
                    turn.finish(TurnState.FAILED, lastText, budgetStop);
                    saveTurnQuietly(turn);
                    lastError = budgetStop;
                    break;
                }
                if (!awaitIfPaused()) {
                    String reason = pendingCancelReason();
                    turn.finish(TurnState.CANCELLED, lastText, reason == null ? "cancelled" : reason);
                    saveTurnQuietly(turn);
                    break;
                }

                emit(HookPoint.STEP_START, TurnEvent.of(this, session, turn, step));
                Instant stepStart = Instant.now();

                String system = PromptAssembler.systemPrompt(this, runtime);
                List<ToolSpec> tools = resolveToolSpecs();
                List<Message> messages = PromptAssembler.messages(this, session);
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
                    saveTurnQuietly(turn);
                    lastError = reason;
                    break;
                }

                ChatResponse response;
                try {
                    Provider provider = runtime.providers().providerFor(event.model());
                    ChatRequest chatRequest = new ChatRequest(event.model(), event.system(), event.messages(),
                            event.tools(), event.maxTokens(), event.temperature(), List.of(), Map.of());
                    response = provider.complete(chatRequest, cancellationSink);
                } catch (ProviderException e) {
                    log.warn("provider call failed: {}", e.getMessage());
                    turn.addStep(new Step(step, stepStart, Instant.now(), "error", 0, 0, 0));
                    turn.finish(TurnState.FAILED, "", e.getMessage());
                    saveTurnQuietly(turn);
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
                stepTotal.incrementAndGet();
                promptTokens.addAndGet(usage.promptTokens());
                completionTokens.addAndGet(usage.completionTokens());
                saveTurnQuietly(turn);
                lastText = response.text();
                touch();

                if (response.hasToolCalls()) {
                    for (ContentBlock.ToolCall call : assistant.toolCalls()) {
                        executeToolCall(turn, call);
                    }
                    continue;
                }
                turn.finish(TurnState.COMPLETED, response.text(), null);
                saveTurnQuietly(turn);
                break;
            }

            if (turn.state() == TurnState.RUNNING) {
                turn.finish(TurnState.MAX_STEPS, lastText, "step budget exhausted");
                saveTurnQuietly(turn);
            }
        } catch (Throwable t) {
            log.error("turn failed unexpectedly", t);
            lastError = String.valueOf(t.getMessage());
            if (turn != null && turn.state() == TurnState.RUNNING) {
                try {
                    turn.finish(TurnState.FAILED, "", String.valueOf(t.getMessage()));
                    saveTurnQuietly(turn);
                } catch (Throwable ignored) {
                    // the session store is failing; the future still completes below
                }
            }
        } finally {
            if (turn != null) {
                emit(HookPoint.TURN_END, TurnEvent.of(this, session, turn));
            }
            busy = false;
            AgentState s = state.get();
            if (s == AgentState.RUNNING || s == AgentState.WAITING) {
                state.set(AgentState.IDLE);
            }
            touch();
            if (sessionDirty) {
                try {
                    namespace.store().update(session);
                } catch (Throwable t) {
                    log.warn("could not persist session metadata", t);
                }
            }
            sub.future().complete(buildResult(turn, turnStart));
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
        for (Tool tool : runtime.tools().resolve(ctx.visibleToolNames())) {
            specs.add(tool.spec());
        }
        return specs;
    }

    private void executeToolCall(Turn turn, ContentBlock.ToolCall call) {
        Map<String, Object> args = Map.of();
        String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, new TypeReference<>() {
            });
            if (parsed != null) {
                args = parsed;
            }
        } catch (Exception e) {
            log.warn("could not parse arguments of tool call \"{}\"; using empty arguments: {}",
                    call.name(), e.getMessage());
        }

        ToolInvocation invocation = new ToolInvocation(call.callId(), call.name(), args, raw);
        Tool tool = runtime.tools().find(call.name()).orElse(null);

        ToolCallEvent before = new ToolCallEvent(this, session, turn, tool, invocation);
        emit(HookPoint.BEFORE_TOOL_CALL, before);

        ToolOutcome outcome;
        if (before.skipped()) {
            outcome = ToolOutcome.denied(before.skipReason());
        } else if (tool == null) {
            outcome = ToolOutcome.error("unknown tool \"" + call.name() + "\"");
        } else {
            String denied = capabilityDenial(tool);
            if (denied == null) {
                denied = budgetToolDenial();
            }
            if (denied != null) {
                outcome = ToolOutcome.denied(denied);
            } else {
                try {
                    budget.updateAndGet(Budget::consumeToolSlot);
                    toolCallTotal.incrementAndGet();
                    ToolContext toolContext = toolContextFactory.create(this,
                            () -> turnCancel || stopFlag, this::currentCancelReason);
                    outcome = tool.invoke(before.invocation(), toolContext);
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

        ToolCallEvent after = new ToolCallEvent(this, session, turn, tool, before.invocation());
        after.setOutcome(outcome);
        emit(HookPoint.AFTER_TOOL_CALL, after);
        ToolOutcome finalOutcome = after.outcome() != null ? after.outcome() : outcome;

        appendMessage(Message.toolResult(call.callId(), call.name(), finalOutcome.ok(),
                finalOutcome.modelText()));
        touch();
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

    private int advertisedMaxTokens(ModelRef model) {
        try {
            Provider provider = runtime.providers().providerFor(model);
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

    private void saveTurnQuietly(Turn turn) {
        try {
            session.saveTurn(turn);
        } catch (Throwable t) {
            log.warn("could not persist turn {}", turn.id(), t);
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

    private void touch() {
        lastActivityAt = Instant.now();
    }
}
