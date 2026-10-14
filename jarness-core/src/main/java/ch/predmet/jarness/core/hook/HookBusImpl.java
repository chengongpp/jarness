package ch.predmet.jarness.core.hook;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.hook.Hook;
import ch.predmet.jarness.api.hook.HookBus;
import ch.predmet.jarness.api.hook.HookContext;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.HookRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Default {@link HookBus}: per-point hook lists ordered by (priority, registration sequence),
 * thread-safe via {@link CopyOnWriteArrayList}.
 *
 * <p>{@link #emit} iterates a snapshot, so hooks may register or close registrations while an
 * event is being dispatched. A throwing hook is logged and skipped — a broken hook must never
 * crash the runtime, and it does not stop later hooks. Only {@code stopPropagation()} does.</p>
 */
public final class HookBusImpl implements HookBus {

    private static final Logger LOG = LoggerFactory.getLogger(HookBusImpl.class);

    private final Supplier<JarnessRuntime> runtime;
    private final Map<HookPoint, CopyOnWriteArrayList<Entry>> hooksByPoint = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private record Entry(Hook hook, long seq) {
    }

    /**
     * Create the bus.
     *
     * @param runtime supplies the running runtime; may yield {@code null} before construction
     */
    public HookBusImpl(Supplier<JarnessRuntime> runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public HookRegistration on(HookPoint point, String name, int priority, Consumer<HookContext> handler) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(handler, "handler");
        String hookName = name != null ? name : "hook-" + sequence.get();
        return register(Hook.of(point, hookName, priority, handler));
    }

    @Override
    public HookRegistration register(Hook hook) {
        Objects.requireNonNull(hook, "hook");
        CopyOnWriteArrayList<Entry> list =
                hooksByPoint.computeIfAbsent(hook.point(), p -> new CopyOnWriteArrayList<>());
        Entry entry = new Entry(hook, sequence.getAndIncrement());
        synchronized (list) {
            int index = 0;
            for (Entry existing : list) {
                if (existing.hook().priority() > hook.priority()) {
                    break;
                }
                index++;
            }
            list.add(Math.min(index, list.size()), entry);
        }
        return new HookRegistration() {
            @Override
            public Hook hook() {
                return entry.hook();
            }

            @Override
            public void close() {
                list.remove(entry);
            }
        };
    }

    @Override
    public HookContext emit(HookPoint point, Object payload) {
        Objects.requireNonNull(point, "point");
        HookContextImpl ctx = new HookContextImpl(point, payload, runtime);
        CopyOnWriteArrayList<Entry> list = hooksByPoint.get(point);
        if (list == null) {
            return ctx;
        }
        for (Entry entry : list) {
            if (ctx.propagationStopped()) {
                break;
            }
            try {
                entry.hook().handle(ctx);
            } catch (Throwable t) {
                LOG.error("hook '{}' at {} threw and was skipped", entry.hook().name(), point, t);
            }
        }
        return ctx;
    }

    @Override
    public List<Hook> hooks(HookPoint point) {
        CopyOnWriteArrayList<Entry> list = hooksByPoint.get(point);
        if (list == null) {
            return List.of();
        }
        return list.stream().map(Entry::hook).toList();
    }

    @Override
    public int size() {
        return hooksByPoint.values().stream().mapToInt(List::size).sum();
    }

    /** Mutable context handed to every hook of one dispatch. */
    private static final class HookContextImpl implements HookContext {

        private final HookPoint point;
        private final Object payload;
        private final Supplier<JarnessRuntime> runtime;
        private volatile boolean cancelled;
        private volatile boolean propagationStopped;
        private volatile String cancelReason = "";

        HookContextImpl(HookPoint point, Object payload, Supplier<JarnessRuntime> runtime) {
            this.point = point;
            this.payload = payload;
            this.runtime = runtime;
        }

        @Override
        public HookPoint point() {
            return point;
        }

        @Override
        public Object payload() {
            return payload;
        }

        @Override
        public JarnessRuntime runtime() {
            return runtime.get();
        }

        @Override
        public void cancel(String reason) {
            if (!cancelled) {
                cancelled = true;
                cancelReason = reason == null ? "" : reason;
            }
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public String cancelReason() {
            return cancelReason;
        }

        @Override
        public void stopPropagation() {
            propagationStopped = true;
        }

        @Override
        public boolean propagationStopped() {
            return propagationStopped;
        }
    }
}
