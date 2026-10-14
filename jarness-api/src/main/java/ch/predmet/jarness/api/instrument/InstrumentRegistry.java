package ch.predmet.jarness.api.instrument;

import java.util.List;
import java.util.Optional;

/**
 * Read view over every instrument the runtime knows, applied or not.
 *
 * <p>Presets are applied by flipping {@link #setEnabled(String, boolean)} before the apply pass, and
 * dynamically loaded mods add instruments after the runtime is already running.</p>
 */
public interface InstrumentRegistry {

    /** @return every instrument in apply order (priority, then mod order, then name). */
    List<Instrument> all();

    /** @return the instrument with this exact name. */
    Optional<Instrument> byName(String name);

    /** @return whether the instrument is enabled under the active preset. */
    boolean isEnabled(String name);

    /**
     * Override the enable decision for one instrument.
     *
     * @param name    instrument name
     * @param enabled whether it should apply
     */
    void setEnabled(String name, boolean enabled);

    /** @return every enabled instrument in apply order. */
    default List<Instrument> enabled() {
        return all().stream().filter(i -> isEnabled(i.name())).toList();
    }
}
