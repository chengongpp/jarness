package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentRequest;
import ch.predmet.jarness.api.agent.AgentSignal;
import ch.predmet.jarness.api.agent.AgentState;
import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.event.AgentEvent;
import ch.predmet.jarness.api.supervisor.AgentStatus;
import ch.predmet.jarness.api.supervisor.Supervisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The default {@link Supervisor}: owns the registry of running agents and their lifecycle.
 *
 * <p>Agents are kept in a lock-guarded {@link LinkedHashMap} so registration order is stable for
 * listings. A {@code STOP} signal is delivered through {@link Agent#stop(String)} so that
 * externally registered agents shut down the same way as supervisor-spawned ones.</p>
 */
public final class SupervisorImpl implements Supervisor {

    private static final Logger log = LoggerFactory.getLogger(SupervisorImpl.class);

    private final Supplier<JarnessRuntime> runtime;
    private final ToolContextFactory toolContexts;
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean running;
    private volatile Instant startedAt;

    /**
     * @param runtime      the runtime, looked up lazily so the supervisor can be constructed
     *                     before the runtime finishes wiring
     * @param toolContexts factory for per-call tool execution contexts
     */
    public SupervisorImpl(Supplier<JarnessRuntime> runtime, ToolContextFactory toolContexts) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.toolContexts = Objects.requireNonNull(toolContexts, "toolContexts");
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

    @Override
    public Agent spawn(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        if (!running) {
            throw new IllegalStateException("supervisor is not running");
        }
        JarnessRuntime rt = runtime.get();
        AgentTemplate template = request.template() != null
                ? request.template()
                : rt.templates().require(request.templateId());
        AgentImpl agent = new AgentImpl(rt, this, request, template, toolContexts);
        emit(rt, HookPoint.AGENT_CREATED, AgentEvent.of(agent, "created"));
        emit(rt, HookPoint.AGENT_STARTING, AgentEvent.of(agent, "starting"));
        lock.lock();
        try {
            agents.put(agent.id(), agent);
        } finally {
            lock.unlock();
        }
        return agent;
    }

    @Override
    public void register(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        if (!running) {
            throw new IllegalStateException("supervisor is not running");
        }
        lock.lock();
        try {
            agents.put(agent.id(), agent);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean unregister(String agentId) {
        Agent removed;
        lock.lock();
        try {
            removed = agents.remove(agentId);
        } finally {
            lock.unlock();
        }
        if (removed == null) {
            return false;
        }
        stopOne(removed, "unregistered");
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
        lock.lock();
        try {
            return Optional.ofNullable(agents.get(agentId));
        } finally {
            lock.unlock();
        }
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
        return agent(agentId).map(Agent::status);
    }

    @Override
    public boolean signal(String agentId, AgentSignal signal) {
        Agent agent = agent(agentId).orElse(null);
        if (agent == null || signal == null) {
            return false;
        }
        deliverSignal(agent, signal);
        return true;
    }

    @Override
    public void signalAll(AgentSignal signal) {
        for (Agent agent : agents()) {
            try {
                deliverSignal(agent, signal);
            } catch (Throwable t) {
                log.warn("could not signal agent {}", agent.id(), t);
            }
        }
    }

    private void deliverSignal(Agent agent, AgentSignal signal) {
        if (signal == AgentSignal.STOP) {
            stopOne(agent, "stop signal");
        } else {
            agent.signal(signal);
        }
    }

    @Override
    public boolean deliver(String agentId, String text) {
        Agent agent = agent(agentId).orElse(null);
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
        List<Agent> snapshot;
        lock.lock();
        try {
            if (!running && agents.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(agents.values());
        } finally {
            lock.unlock();
        }
        signalAll(AgentSignal.STOP);
        awaitAll(Duration.ofSeconds(5L));
        for (Agent agent : snapshot) {
            stopOne(agent, "shutdown");
        }
        lock.lock();
        try {
            agents.clear();
            running = false;
        } finally {
            lock.unlock();
        }
    }

    private void stopOne(Agent agent, String reason) {
        try {
            if (agent instanceof AgentImpl impl) {
                impl.markStopped(reason);
            } else {
                agent.stop(reason);
            }
        } catch (Throwable t) {
            log.warn("could not stop agent {}", agent.id(), t);
        }
    }

    private void emit(JarnessRuntime rt, HookPoint point, Object payload) {
        try {
            rt.hooks().emit(point, payload);
        } catch (Throwable t) {
            log.warn("hook dispatch at {} failed", point, t);
        }
    }
}
