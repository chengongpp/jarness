package ch.predmet.jarness.core.loop;

import ch.predmet.jarness.api.loop.LoopHook;
import ch.predmet.jarness.api.loop.LoopHookRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Default ordered loop-hook registry. */
public final class LoopHookRegistryImpl implements LoopHookRegistry {

    private final Map<String, LoopHook> hooks = new LinkedHashMap<>();

    @Override
    public synchronized void register(LoopHook hook) {
        if (hook != null) {
            hooks.put(hook.name(), hook);
        }
    }

    @Override
    public synchronized boolean unregister(String name) {
        return hooks.remove(name) != null;
    }

    @Override
    public synchronized List<LoopHook> all() {
        List<LoopHook> list = new ArrayList<>(hooks.values());
        list.sort(Comparator.comparingInt(LoopHook::order).thenComparing(LoopHook::name));
        return List.copyOf(list);
    }

    @Override
    public synchronized Optional<LoopHook> byName(String name) {
        return Optional.ofNullable(hooks.get(name));
    }
}
