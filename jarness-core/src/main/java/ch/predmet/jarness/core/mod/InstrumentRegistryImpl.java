package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.instrument.Instrument;
import ch.predmet.jarness.api.instrument.InstrumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link InstrumentRegistry}: instruments with a per-instrument enable flag.
 *
 * <p>An instrument is enabled by its author's {@code autoEnable} until
 * {@link #setEnabled(String, boolean)} overrides the decision. {@link #all()} is sorted by
 * (priority, owning mod load order, name); the loader feeds mod order through
 * {@link #noteModOrder(String, int)}.</p>
 */
public final class InstrumentRegistryImpl implements InstrumentRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(InstrumentRegistryImpl.class);

    private final Map<String, Instrument> instruments = new LinkedHashMap<>();
    private final Map<String, Boolean> enabledOverrides = new HashMap<>();
    private final Map<String, Integer> modOrder = new HashMap<>();

    /**
     * Add an instrument to the registry.
     *
     * @param instrument the instrument; an existing instrument with the same name is replaced
     */
    public synchronized void register(Instrument instrument) {
        Instrument previous = instruments.put(instrument.name(), instrument);
        if (previous != null && previous != instrument) {
            LOG.warn("instrument '{}' re-registered by mod '{}', replacing mod '{}'",
                    instrument.name(), instrument.modId(), previous.modId());
        }
    }

    /**
     * Record the load order of a mod, used to break priority ties between instruments.
     *
     * @param modId the mod id
     * @param order 0-based load order
     */
    public synchronized void noteModOrder(String modId, int order) {
        modOrder.put(modId, order);
    }

    @Override
    public synchronized List<Instrument> all() {
        return instruments.values().stream()
                .sorted(Comparator.comparingInt(Instrument::priority)
                        .thenComparingInt(i -> modOrder.getOrDefault(i.modId(), Integer.MAX_VALUE))
                        .thenComparing(Instrument::name))
                .toList();
    }

    @Override
    public synchronized Optional<Instrument> byName(String name) {
        return Optional.ofNullable(instruments.get(name));
    }

    @Override
    public synchronized boolean isEnabled(String name) {
        Instrument instrument = instruments.get(name);
        if (instrument == null) {
            return false;
        }
        Boolean override = enabledOverrides.get(name);
        return override != null ? override : instrument.autoEnable();
    }

    @Override
    public synchronized void setEnabled(String name, boolean enabled) {
        enabledOverrides.put(name, enabled);
    }
}
