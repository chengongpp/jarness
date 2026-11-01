package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentSignal;
import ch.predmet.jarness.api.agent.AgentState;
import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilityDerivation;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.event.AgentEvent;
import ch.predmet.jarness.api.hook.event.SpawnEvent;
import ch.predmet.jarness.api.kernel.AgentManifest;
import ch.predmet.jarness.api.kernel.AgentTree;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.kernel.ExitStatus;
import ch.predmet.jarness.api.kernel.OrphanPolicy;
import ch.predmet.jarness.api.kernel.Origin;
import ch.predmet.jarness.api.kernel.ResourceStats;
import ch.predmet.jarness.api.kernel.SpawnDeniedException;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceRegistry;
import ch.predmet.jarness.api.namespace.NamespaceSpec;
import ch.predmet.jarness.api.supervisor.AgentStatus;
import ch.predmet.jarness.api.supervisor.Supervisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The default {@link Supervisor}: a supervision tree with a synthetic root at pid {@code 0}.
 *
 * <p>Every agent is a node: it has exactly one parent, exactly one namespace, a capability set that
 * is the intersection of what it requested, what its parent holds, and what the namespace grants,
 * and a budget that fits inside its parent's remaining one. Spawning validates depth, width,
 * namespace boundary, capability attenuation, and budget before a node is admitted; refusals are
 * emitted as {@link HookPoint#SPAWN_DENIED} events and thrown as
 * {@link SpawnDeniedException}.</p>
 *
 * <p>When a node stops, its subtree is killed ({@link OrphanPolicy#KILL}). Nodes stay visible in
 * the tree after they stop, so an observer can still see what happened.</p>
 */
public final class SupervisorImpl implements Supervisor {

    private static final Logger log = LoggerFactory.getLogger(SupervisorImpl.class);
    private static final Logger audit = LoggerFactory.getLogger("ch.predmet.jarness.supervisor");

    /** The synthetic root: it is not an agent, but it is the parent of every root agent. */
    public static final long ROOT_PID = 0L;

    private final Supplier<JarnessRuntime> runtime;
    private final ToolContextFactory toolContexts;
    private final NamespaceRegistry namespaces;
    private final int maxDepth;
    private final int maxChildren;

    private final Map<Long, Agent> agents = new LinkedHashMap<>();
    private final Map<Long, List<Long>> children = new HashMap<>();
    private final Map<String, Long> capOwner = new HashMap<>();
    private final Set<String> knownCaps = new LinkedHashSet<>();
    private final CapabilityDerivation derivation = new CapabilityDerivation();
    private final CapabilitySet rootCaps = CapabilitySet.root();
    private final AtomicLong nextPid = new AtomicLong(1L);
    private final AtomicLong nextCapId = new AtomicLong(1L);
    private final ReentrantLock lock = new ReentrantLock();

    private volatile boolean running;
    private volatile boolean shuttingDown;
    private volatile Instant startedAt;

    /**
     * @param runtime      the runtime, looked up lazily so the supervisor can be constructed
     *                     before the runtime finishes wiring
     * @param toolContexts factory for per-call tool execution contexts
     * @param namespaces   the namespace table
     * @param maxDepth     maximum tree depth, {@code 0} for unbounded
     * @param maxChildren  maximum direct children per node, {@code 0} for unbounded
     */
    public SupervisorImpl(Supplier<JarnessRuntime> runtime, ToolContextFactory toolContexts,
                          NamespaceRegistry namespaces, int maxDepth, int maxChildren) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.toolContexts = Objects.requireNonNull(toolContexts, "toolContexts");
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.maxDepth = maxDepth;
        this.maxChildren = maxChildren;
        for (Capability capability : rootCaps.capabilities()) {
            knownCaps.add(capability.capId());
        }
    }

    @Override
    public NamespaceRegistry namespaces() {
        return namespaces;
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (running) {
                return;
            }
            running = true;
            startedAt = Instant.now();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Instant startedAt() {
        return startedAt;
    }

    // ------------------------------------------------------------- spawn

    @Override
    public Agent spawn(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        requireRunning();
        long parentPid = request.parentPid();
        Agent parent = parentPid == ROOT_PID ? null : agentOrNull(parentPid);
        String namespaceId = request.namespaceId() != null
                ? request.namespaceId()
                : parent != null ? parent.namespaceId() : Namespace.DEFAULT_ID;
        String templateId = request.template() != null ? request.template().id() : request.templateId();
        AgentManifest manifest = AgentManifest.builder(templateId)
                .name(request.name())
                .origin(Origin.FRESH)
                .build();
        long pid = spawnInternal(parentPid, namespaceId, manifest, request.capabilities(), request.budget(),
                request, null);
        return requireAgent(pid);
    }

    @Override
    public long spawn(long parentPid, String namespaceId, AgentManifest manifest, CapabilitySet requestedCaps,
                      Budget budget) {
        requireRunning();
        Objects.requireNonNull(manifest, "manifest");
        return spawnInternal(parentPid, namespaceId, manifest, requestedCaps, budget, null, null);
    }

    @Override
    public long fork(long parentPid, String namespaceId, AgentManifest manifest, CapabilitySet requestedCaps,
                     Budget budget) {
        requireRunning();
        Objects.requireNonNull(manifest, "manifest");
        Agent parent = agentOrNull(parentPid);
        if (parent == null) {
            throw deny(SpawnDeniedException.Reason.PARENT,
                    "cannot fork from unknown parent pid " + parentPid, parentPid, namespaceId, manifest);
        }
        AgentManifest forked = manifest.asFork();
        if (forked.templateId() == null) {
            forked = AgentManifest.builder(parent.template().id())
                    .name(forked.name())
                    .loopRef(forked.loopRef())
                    .restart(forked.restart())
                    .orphan(forked.orphan())
                    .origin(Origin.FORK)
                    .modelProfile(forked.modelProfileId())
                    .build();
        }
        String nsId = namespaceId != null ? namespaceId : parent.namespaceId();
        CapabilitySet requested = requestedCaps != null ? requestedCaps : parent.capabilities();
        Budget childBudget = budget != null ? budget : parent.budget().childDefault();
        return spawnInternal(parentPid, nsId, forked, requested, childBudget, null, parent);
    }

    /**
     * The single spawn path: validate, construct, register, and emit.
     *
     * @param parentPid       parent pid, {@code 0} for a root agent
     * @param requestedNs     requested namespace, or {@code null} to inherit
     * @param manifest        the manifest
     * @param requestedCaps   requested capabilities, or {@code null} to inherit
     * @param requestedBudget requested budget, or {@code null} for the child default
     * @param request         convenience creation input, or {@code null} for a bare spawn
     * @param forkFrom        the agent to copy the image from, or {@code null} for a fresh agent
     * @return the new pid
     */
    private long spawnInternal(long parentPid, String requestedNs, AgentManifest manifest,
                               CapabilitySet requestedCaps, Budget requestedBudget, AgentRequest request,
                               Agent forkFrom) {
        lock.lock();
        try {
            if (!running) {
                throw deny(SpawnDeniedException.Reason.NOT_RUNNING, "supervisor is not running",
                        parentPid, requestedNs, manifest);
            }
            Agent parent = parentPid == ROOT_PID ? null : agents.get(parentPid);
            if (parentPid != ROOT_PID && parent == null) {
                throw deny(SpawnDeniedException.Reason.PARENT,
                        "unknown parent pid " + parentPid, parentPid, requestedNs, manifest);
            }
            if (parent != null && (parent.state() == AgentState.STOPPED || parent.state() == AgentState.FAILED)) {
                throw deny(SpawnDeniedException.Reason.PARENT,
                        "parent " + parentPid + " is " + parent.state(), parentPid, requestedNs, manifest);
            }
            String nsId = requestedNs != null ? requestedNs
                    : parent != null ? parent.namespaceId() : Namespace.DEFAULT_ID;
            Namespace namespace = namespaces.find(nsId).orElse(null);
            if (namespace == null) {
                throw deny(SpawnDeniedException.Reason.NAMESPACE,
                        "unknown namespace \"" + nsId + "\"", parentPid, nsId, manifest);
            }
            if (parent != null && !parent.namespaceId().equals(nsId)
                    && !parent.capabilities().containsKind(CapKind.NAMESPACE)) {
                throw deny(SpawnDeniedException.Reason.NAMESPACE,
                        "agent " + parentPid + " may not cross from namespace " + parent.namespaceId()
                                + " into " + nsId, parentPid, nsId, manifest);
            }
            int depth = parent == null ? 1 : parent.depth() + 1;
            if (maxDepth > 0 && depth > maxDepth) {
                throw deny(SpawnDeniedException.Reason.DEPTH,
                        "depth " + depth + " exceeds the limit of " + maxDepth, parentPid, nsId, manifest);
            }
            long activeChildren = activeChildCount(parentPid);
            if (maxChildren > 0 && activeChildren >= maxChildren) {
                throw deny(SpawnDeniedException.Reason.CHILDREN,
                        "parent " + parentPid + " already has " + activeChildren + " active children (limit "
                                + maxChildren + ")", parentPid, nsId, manifest);
            }
            if (manifest.orphan() != OrphanPolicy.KILL) {
                throw deny(SpawnDeniedException.Reason.CAPABILITY,
                        "orphan policy " + manifest.orphan() + " is not implemented; only KILL is accepted",
                        parentPid, nsId, manifest);
            }
            if (parent != null) {
                if (!parent.capabilities().containsKind(CapKind.SPAWN)) {
                    throw deny(SpawnDeniedException.Reason.CAPABILITY,
                            "agent " + parentPid + " does not hold the spawn capability",
                            parentPid, nsId, manifest);
                }
                if (!parent.budget().allowsSpawn()) {
                    throw deny(SpawnDeniedException.Reason.BUDGET,
                            "agent " + parentPid + " has exhausted its spawn depth", parentPid, nsId, manifest);
                }
                if (parent.budget().expired()) {
                    throw deny(SpawnDeniedException.Reason.BUDGET,
                            "agent " + parentPid + " is past its deadline", parentPid, nsId, manifest);
                }
            }
            Budget budget = requestedBudget != null ? requestedBudget
                    : parent == null ? Budget.unlimited() : parent.budget().childDefault();
            if (parent != null && !budget.fitsWithin(parent.budget())) {
                throw deny(SpawnDeniedException.Reason.BUDGET,
                        "requested budget (" + budget.describe() + ") does not fit within parent budget ("
                                + parent.budget().describe() + ")", parentPid, nsId, manifest);
            }
            AgentTemplate template = resolveTemplate(manifest, namespace, request, forkFrom, parentPid, nsId);
            if (manifest.modelProfileId() != null && namespace.metadata().profile(manifest.modelProfileId()) == null
                    && (request == null || request.model() == null)) {
                throw deny(SpawnDeniedException.Reason.MODEL,
                        "namespace " + nsId + " has no model profile \"" + manifest.modelProfileId() + "\"",
                        parentPid, nsId, manifest);
            }
            CapabilitySet base = parent == null ? rootCaps : parent.capabilities();
            CapabilitySet requested = requestedCaps != null ? requestedCaps : base;
            CapabilitySet effective = requested
                    .intersect(base)
                    .intersect(namespace.metadata().defaults());
            long pid = nextPid.getAndIncrement();
            CapabilitySet assigned = assignCapIds(effective, base, pid);
            AgentRequest normalized = request != null ? request
                    : AgentRequest.builder(template.id())
                            .name(manifest.name())
                            .parent(parentPid)
                            .namespace(nsId)
                            .build();
            Agent agent;
            try {
                agent = new AgentImpl(runtime.get(), this, pid, parent, depth, namespace, manifest, assigned,
                        budget, normalized, template, toolContexts, forkFrom instanceof AgentImpl impl ? impl : null);
            } catch (RuntimeException e) {
                releaseCaps(assigned);
                throw e;
            }
            agents.put(pid, agent);
            children.computeIfAbsent(parentPid, key -> new ArrayList<>()).add(pid);
            audit.info("spawned pid={} parent={} ns={} template={} caps={} budget=[{}]",
                    pid, parentPid, nsId, template.id(), assigned.kinds(), budget.describe());
            emit(HookPoint.AGENT_CREATED, AgentEvent.of(agent, "created"));
            emit(HookPoint.AGENT_STARTING, AgentEvent.of(agent, "starting"));
            return pid;
        } finally {
            lock.unlock();
        }
    }

    private AgentTemplate resolveTemplate(AgentManifest manifest, Namespace namespace, AgentRequest request,
                                          Agent forkFrom, long parentPid, String nsId) {
        AgentTemplate template;
        if (request != null && request.template() != null) {
            template = request.template();
        } else {
            String templateId = manifest.templateId();
            String requiredVersion = manifest.templateVersion();
            if (templateId == null) {
                templateId = namespace.defaultTemplateId();
                requiredVersion = manifest.templateVersion().isBlank()
                        ? namespace.defaultTemplateVersion() : requiredVersion;
            }
            if (templateId == null || templateId.isBlank()) {
                throw deny(SpawnDeniedException.Reason.TEMPLATE,
                        "namespace " + nsId + " declares no default template and the manifest names none",
                        parentPid, nsId, manifest);
            }
            template = runtime.get().templates().find(templateId).orElse(null);
            if (template == null) {
                throw deny(SpawnDeniedException.Reason.TEMPLATE,
                        "unknown template \"" + templateId + "\"", parentPid, nsId, manifest);
            }
            if (!requiredVersion.isBlank() && !requiredVersion.equals(template.version())) {
                throw deny(SpawnDeniedException.Reason.TEMPLATE,
                        "template \"" + templateId + "\" is version " + template.version()
                                + ", but version " + requiredVersion + " was required",
                        parentPid, nsId, manifest);
            }
        }
        if (forkFrom != null && request == null && manifest.templateId() == null) {
            template = forkFrom.template();
        }
        return template;
    }

    private CapabilitySet assignCapIds(CapabilitySet effective, CapabilitySet parentCaps, long pid) {
        if (effective.isEmpty()) {
            return effective;
        }
        List<String> ids = new ArrayList<>(effective.size());
        for (Capability capability : effective.capabilities()) {
            String id = "cap-" + nextCapId.getAndIncrement();
            ids.add(id);
            String parentId = null;
            for (Capability candidate : parentCaps.capabilities()) {
                if (candidate.covers(capability.asRequest())) {
                    parentId = candidate.capId();
                    break;
                }
            }
            derivation.add(id, parentId);
            capOwner.put(id, pid);
            knownCaps.add(id);
        }
        return effective.reId(ids);
    }

    private void releaseCaps(CapabilitySet grants) {
        lock.lock();
        try {
            releaseCapsLocked(grants);
        } finally {
            lock.unlock();
        }
    }

    private void releaseCapsLocked(CapabilitySet grants) {
        for (Capability capability : grants.capabilities()) {
            capOwner.remove(capability.capId());
            derivation.forget(capability.capId());
            knownCaps.remove(capability.capId());
        }
    }

    private SpawnDeniedException deny(SpawnDeniedException.Reason reason, String message, long parentPid,
                                      String namespaceId, AgentManifest manifest) {
        audit.warn("spawn denied: {} (parent={}, ns={}): {}", reason, parentPid, namespaceId, message);
        emit(HookPoint.SPAWN_DENIED, new SpawnEvent(parentPid, namespaceId, manifest, reason, message));
        return new SpawnDeniedException(reason, message, parentPid, namespaceId);
    }

    // ------------------------------------------------------------- registry

    @Override
    public void register(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        requireRunning();
        lock.lock();
        try {
            agents.put(agent.pid(), agent);
            children.computeIfAbsent(agent.parentPid(), key -> new ArrayList<>()).add(agent.pid());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean unregister(String agentId) {
        Agent agent = agentOrNull(agentId);
        if (agent == null) {
            return false;
        }
        stopSubtree(agent.pid(), "unregistered");
        lock.lock();
        try {
            List<Long> subtree = subtreePidsLocked(agent.pid());
            for (Long pid : subtree) {
                agents.remove(pid);
                children.remove(pid);
                children.values().forEach(list -> list.remove(pid));
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    @Override
    public List<Agent> agents() {
        lock.lock();
        try {
            return List.copyOf(agents.values());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Agent> agent(String agentId) {
        return Optional.ofNullable(agentOrNull(agentId));
    }

    @Override
    public Agent agent(long pid) {
        return requireAgent(pid);
    }

    @Override
    public List<AgentStatus> statuses() {
        List<AgentStatus> out = new ArrayList<>();
        for (Agent agent : agents()) {
            try {
                out.add(agent.status());
            } catch (Throwable t) {
                log.warn("could not read status of agent {}", agent.id(), t);
            }
        }
        return out;
    }

    @Override
    public Optional<AgentStatus> status(String agentId) {
        Agent agent = agentOrNull(agentId);
        return agent == null ? Optional.empty() : Optional.of(agent.status());
    }

    @Override
    public Optional<AgentStatus> status(long pid) {
        Agent agent = agentOrNull(pid);
        return agent == null ? Optional.empty() : Optional.of(agent.status());
    }

    // ------------------------------------------------------------- signals

    @Override
    public boolean signal(String agentId, AgentSignal signal) {
        Agent agent = agentOrNull(agentId);
        return agent != null && signal(agent.pid(), signal, false);
    }

    @Override
    public boolean signal(long pid, AgentSignal signal, boolean subtree) {
        Agent agent = agentOrNull(pid);
        if (agent == null || signal == null) {
            return false;
        }
        if (signal == AgentSignal.STOP) {
            stopSubtree(pid, "stop signal");
            return true;
        }
        List<Long> targets = subtree ? subtreePids(pid) : List.of(pid);
        for (Long target : targets) {
            Agent node = agentOrNull(target);
            if (node == null || node.state() == AgentState.STOPPED || node.state() == AgentState.FAILED) {
                continue;
            }
            try {
                node.signal(signal);
            } catch (Throwable t) {
                log.warn("could not signal agent {}", target, t);
            }
        }
        return true;
    }

    @Override
    public void signalAll(AgentSignal signal) {
        for (Agent agent : agents()) {
            try {
                if (signal == AgentSignal.STOP) {
                    stopSubtree(agent.pid(), "stop signal");
                } else {
                    agent.signal(signal);
                }
            } catch (Throwable t) {
                log.warn("could not signal agent {}", agent.id(), t);
            }
        }
    }

    @Override
    public boolean deliver(String agentId, String text) {
        Agent agent = agentOrNull(agentId);
        if (agent == null) {
            return false;
        }
        AgentState state = agent.state();
        if (state == AgentState.STOPPED || state == AgentState.FAILED) {
            return false;
        }
        return agent.submit(text) != null;
    }

    @Override
    public boolean awaitAll(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            boolean drained = true;
            for (Agent agent : agents()) {
                AgentState state = agent.state();
                if (state == AgentState.STOPPED || state == AgentState.FAILED) {
                    continue;
                }
                if (agent.isBusy() || agent.status().queuedInputs() > 0) {
                    drained = false;
                    break;
                }
            }
            if (drained) {
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
    public Optional<ExitStatus> await(long pid, Duration timeout) {
        Agent agent = agentOrNull(pid);
        if (agent == null) {
            return Optional.empty();
        }
        return agent.awaitExit(timeout);
    }

    // ------------------------------------------------------------- tree / stats

    @Override
    public AgentTree tree(long pid) {
        if (pid == ROOT_PID) {
            List<AgentTree> roots = new ArrayList<>();
            for (Long child : childPids(ROOT_PID)) {
                Agent agent = agentOrNull(child);
                if (agent != null) {
                    roots.add(node(agent));
                }
            }
            return new AgentTree(ROOT_PID, 0, "super", Namespace.DEFAULT_ID, AgentState.RUNNING, roots);
        }
        return node(requireAgent(pid));
    }

    private AgentTree node(Agent agent) {
        List<AgentTree> kids = new ArrayList<>();
        for (Long child : childPids(agent.pid())) {
            Agent childAgent = agentOrNull(child);
            if (childAgent != null) {
                kids.add(node(childAgent));
            }
        }
        return new AgentTree(agent.pid(), agent.parentPid(), agent.name(), agent.namespaceId(),
                agent.state(), kids);
    }

    @Override
    public ResourceStats stats(long pid) {
        if (pid == ROOT_PID) {
            int turns = 0;
            int steps = 0;
            int toolCalls = 0;
            long prompt = 0;
            long completion = 0;
            int total = 0;
            for (Agent agent : agents()) {
                AgentStatus status = agent.status();
                turns += status.turns();
                steps += status.steps();
                toolCalls += status.toolCalls();
                prompt += status.promptTokens();
                completion += status.completionTokens();
                total++;
            }
            return new ResourceStats(ROOT_PID, 0, childPids(ROOT_PID).size(), Math.max(0, total - 1),
                    turns, steps, toolCalls, prompt, completion, -1L, startedAt,
                    startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now()));
        }
        Agent root = requireAgent(pid);
        int turns = 0;
        int steps = 0;
        int toolCalls = 0;
        long prompt = 0;
        long completion = 0;
        List<Long> subtree = subtreePids(pid);
        for (Long nodePid : subtree) {
            Agent agent = agentOrNull(nodePid);
            if (agent == null) {
                continue;
            }
            AgentStatus status = agent.status();
            turns += status.turns();
            steps += status.steps();
            toolCalls += status.toolCalls();
            prompt += status.promptTokens();
            completion += status.completionTokens();
        }
        return new ResourceStats(pid, root.depth(), childPids(pid).size(), Math.max(0, subtree.size() - 1),
                turns, steps, toolCalls, prompt, completion, root.budget().tokensLeft(), root.status().startedAt(),
                Duration.between(root.status().startedAt(), Instant.now()));
    }

    @Override
    public boolean revokeCap(String capId) {
        lock.lock();
        try {
            if (capId == null || !knownCaps.contains(capId)) {
                return false;
            }
            Set<String> revoked = derivation.revoke(capId);
            Map<Long, Set<String>> byAgent = new HashMap<>();
            for (String id : revoked) {
                Long owner = capOwner.remove(id);
                knownCaps.remove(id);
                if (owner != null) {
                    byAgent.computeIfAbsent(owner, key -> new LinkedHashSet<>()).add(id);
                }
            }
            for (Map.Entry<Long, Set<String>> entry : byAgent.entrySet()) {
                Agent agent = agents.get(entry.getKey());
                if (agent instanceof AgentImpl impl) {
                    impl.revokeCaps(entry.getValue());
                }
            }
            audit.info("revoked capability {} and {} derived grant(s) across {} agent(s)",
                    capId, revoked.size() - 1, byAgent.size());
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------- namespaces

    @Override
    public Namespace createNamespace(NamespaceSpec spec) {
        Namespace namespace = namespaces.create(spec);
        audit.info("created namespace {}", namespace.id());
        return namespace;
    }

    @Override
    public boolean removeNamespace(String id) {
        long live;
        lock.lock();
        try {
            live = agents.values().stream()
                    .filter(a -> a.namespaceId().equals(id))
                    .filter(a -> a.state() != AgentState.STOPPED && a.state() != AgentState.FAILED)
                    .count();
        } finally {
            lock.unlock();
        }
        if (live > 0) {
            throw new IllegalStateException("namespace \"" + id + "\" still has " + live + " live agent(s)");
        }
        boolean removed = namespaces.remove(id);
        if (removed) {
            audit.info("removed namespace {}", id);
        }
        return removed;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public int size() {
        lock.lock();
        try {
            return agents.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void shutdown() {
        List<Long> snapshot;
        lock.lock();
        try {
            if (!running && agents.isEmpty()) {
                return;
            }
            shuttingDown = true;
            snapshot = new ArrayList<>(agents.keySet());
        } finally {
            lock.unlock();
        }
        snapshot.sort(Comparator.comparingLong(this::depthOf).reversed());
        for (Long pid : snapshot) {
            Agent agent = agentOrNull(pid);
            if (agent == null) {
                continue;
            }
            try {
                if (agent instanceof AgentImpl impl) {
                    impl.markStopped("shutdown");
                } else if (agent.state() != AgentState.STOPPED && agent.state() != AgentState.FAILED) {
                    agent.stop("shutdown");
                }
            } catch (Throwable t) {
                log.warn("could not stop agent {}", pid, t);
            }
        }
        lock.lock();
        try {
            agents.clear();
            children.clear();
            capOwner.clear();
            knownCaps.clear();
            rootCaps.capabilities().forEach(c -> knownCaps.add(c.capId()));
            running = false;
            shuttingDown = false;
            startedAt = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by an agent when its worker has exited, so that the parent-death rule holds even when
     * the agent was stopped directly rather than through the supervisor.
     *
     * @param agent  the agent that exited
     * @param reason short reason
     */
    void agentTerminated(Agent agent, String reason) {
        if (shuttingDown) {
            return;
        }
        for (Long child : childPids(agent.pid())) {
            stopSubtree(child, "parent " + agent.pid() + " exited: " + reason);
        }
        releaseCaps(agent.capabilities());
    }

    private void stopSubtree(long pid, String reason) {
        List<Long> subtree = subtreePids(pid);
        subtree.sort(Comparator.comparingLong(this::depthOf).reversed());
        for (Long nodePid : subtree) {
            Agent agent = agentOrNull(nodePid);
            if (agent == null || agent.state() == AgentState.STOPPED || agent.state() == AgentState.FAILED) {
                continue;
            }
            try {
                if (agent instanceof AgentImpl impl) {
                    impl.markStopped(reason);
                } else {
                    agent.stop(reason);
                }
            } catch (Throwable t) {
                log.warn("could not stop agent {}", nodePid, t);
            }
        }
    }

    private int depthOf(long pid) {
        Agent agent = agentOrNull(pid);
        return agent == null ? 0 : agent.depth();
    }

    // ------------------------------------------------------------- helpers

    private void requireRunning() {
        if (!running) {
            throw new IllegalStateException("supervisor is not running");
        }
    }

    private Agent agentOrNull(String agentId) {
        lock.lock();
        try {
            for (Agent agent : agents.values()) {
                if (agent.id().equals(agentId)) {
                    return agent;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private Agent agentOrNull(long pid) {
        lock.lock();
        try {
            return agents.get(pid);
        } finally {
            lock.unlock();
        }
    }

    private Agent requireAgent(long pid) {
        Agent agent = agentOrNull(pid);
        if (agent == null) {
            throw new IllegalArgumentException("unknown pid " + pid);
        }
        return agent;
    }

    private List<Long> childPids(long pid) {
        lock.lock();
        try {
            List<Long> kids = children.get(pid);
            return kids == null ? List.of() : List.copyOf(kids);
        } finally {
            lock.unlock();
        }
    }

    private long activeChildCount(long pid) {
        long count = 0;
        for (Long child : childPids(pid)) {
            Agent agent = agentOrNull(child);
            if (agent != null && agent.state() != AgentState.STOPPED && agent.state() != AgentState.FAILED) {
                count++;
            }
        }
        return count;
    }

    private List<Long> subtreePids(long pid) {
        lock.lock();
        try {
            return subtreePidsLocked(pid);
        } finally {
            lock.unlock();
        }
    }

    private List<Long> subtreePidsLocked(long pid) {
        List<Long> out = new ArrayList<>();
        List<Long> stack = new ArrayList<>();
        stack.add(pid);
        while (!stack.isEmpty()) {
            Long current = stack.remove(stack.size() - 1);
            if (out.contains(current)) {
                continue;
            }
            out.add(current);
            List<Long> kids = children.get(current);
            if (kids != null) {
                stack.addAll(kids);
            }
        }
        return out;
    }

    private void emit(HookPoint point, Object payload) {
        try {
            runtime.get().hooks().emit(point, payload);
        } catch (Throwable t) {
            log.warn("hook dispatch at {} failed", point, t);
        }
    }
}
