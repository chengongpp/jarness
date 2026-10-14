package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.agent.AgentContext;
import ch.predmet.jarness.api.hook.HookContext;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.HookRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Default {@link AgentContext} implementation: named prompt sections, tool visibility, scoped
 * hooks, and a scratch map, all thread-safe for concurrent use by mods and the agent loop.
 *
 * <p>Tool visibility starts as the template's tool list, set once by the owning agent through
 * {@link #baseTools(List)}. {@link #visibleToolNames()} preserves the template's declaration order
 * and reports only the currently allowed tools; tools allowed beyond the template set are appended
 * in the order they were allowed.</p>
 */
public final class AgentContextImpl implements AgentContext {

    private record Section(String name, int order, Supplier<String> text) {
    }

    private final Agent agent;
    private final JarnessRuntime runtime;
    private final Logger log;

    private final ConcurrentHashMap<String, Section> systemSections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Section> userSections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> scratch = new ConcurrentHashMap<>();
    private final List<HookRegistration> hookRegistrations = new CopyOnWriteArrayList<>();

    private final Object toolLock = new Object();
    private final List<String> base = new ArrayList<>();
    private LinkedHashSet<String> visible = new LinkedHashSet<>();

    /**
     * @param agent   the owning agent
     * @param runtime the running runtime
     */
    public AgentContextImpl(Agent agent, JarnessRuntime runtime) {
        this.agent = agent;
        this.runtime = runtime;
        this.log = LoggerFactory.getLogger("ch.predmet.jarness.agent." + agent.id());
    }

    @Override
    public Agent agent() {
        return agent;
    }

    @Override
    public JarnessRuntime runtime() {
        return runtime;
    }

    @Override
    public Logger log() {
        return log;
    }

    @Override
    public void systemPromptSection(String name, int order, Supplier<String> text) {
        if (name == null || name.isBlank() || text == null) {
            return;
        }
        systemSections.put(name, new Section(name, order, text));
    }

    @Override
    public void userContext(String name, int order, Supplier<String> text) {
        if (name == null || name.isBlank() || text == null) {
            return;
        }
        userSections.put(name, new Section(name, order, text));
    }

    /**
     * Set the template's tool set: the declaration order {@link #visibleToolNames()} preserves and
     * the initial visible set.
     *
     * @param names tool names in template order
     */
    void baseTools(List<String> names) {
        synchronized (toolLock) {
            base.clear();
            LinkedHashSet<String> next = new LinkedHashSet<>();
            if (names != null) {
                for (String name : names) {
                    if (name != null && !name.isBlank() && !base.contains(name)) {
                        base.add(name);
                        next.add(name);
                    }
                }
            }
            visible = next;
        }
    }

    @Override
    public void restrictTools(Collection<String> names) {
        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (runtime.tools().contains(name)) {
                    next.add(name);
                } else {
                    log.warn("restrictTools: unknown tool \"{}\" ignored", name);
                }
            }
        }
        synchronized (toolLock) {
            visible = next;
        }
    }

    @Override
    public void allowTool(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (!runtime.tools().contains(name)) {
            log.warn("allowTool: unknown tool \"{}\" ignored", name);
            return;
        }
        synchronized (toolLock) {
            visible.add(name);
        }
    }

    @Override
    public void denyTool(String name) {
        synchronized (toolLock) {
            visible.remove(name);
        }
    }

    @Override
    public List<String> visibleToolNames() {
        synchronized (toolLock) {
            List<String> out = new ArrayList<>();
            for (String name : base) {
                if (visible.contains(name)) {
                    out.add(name);
                }
            }
            for (String name : visible) {
                if (!base.contains(name)) {
                    out.add(name);
                }
            }
            return List.copyOf(out);
        }
    }

    @Override
    public HookRegistration on(HookPoint point, String name, int priority, Consumer<HookContext> handler) {
        HookRegistration registration = runtime.hooks().on(point, name, priority, handler);
        hookRegistrations.add(registration);
        return registration;
    }

    /** Detach every hook registered through this context. Called by the agent on stop. */
    void closeHooks() {
        for (HookRegistration registration : hookRegistrations) {
            try {
                registration.close();
            } catch (Throwable t) {
                log.warn("could not detach scoped hook {}", registration.hook().name(), t);
            }
        }
        hookRegistrations.clear();
    }

    @Override
    public void put(String key, Object value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            scratch.remove(key);
        } else {
            scratch.put(key, value);
        }
    }

    @Override
    public Optional<Object> get(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(scratch.get(key));
    }

    /** @return the current text of the system-prompt sections, in ascending order. */
    List<String> systemPromptTexts() {
        return resolveSections(systemSections);
    }

    /** @return the current text of the user-context sections, in ascending order. */
    List<String> userContextTexts() {
        return resolveSections(userSections);
    }

    private List<String> resolveSections(Map<String, Section> sections) {
        List<Section> sorted = new ArrayList<>(sections.values());
        sorted.sort(Comparator.comparingInt(Section::order).thenComparing(Section::name));
        List<String> out = new ArrayList<>(sorted.size());
        for (Section section : sorted) {
            try {
                String text = section.text().get();
                if (text != null && !text.isBlank()) {
                    out.add(text);
                }
            } catch (Throwable t) {
                log.warn("prompt section \"{}\" supplier failed", section.name(), t);
            }
        }
        return out;
    }
}
