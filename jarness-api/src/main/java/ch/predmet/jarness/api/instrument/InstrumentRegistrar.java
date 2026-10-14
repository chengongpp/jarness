package ch.predmet.jarness.api.instrument;

import java.util.function.Consumer;

/**
 * Per-mod registration of instruments.
 *
 * @see ch.predmet.jarness.api.mod.ModContext#instruments()
 */
public interface InstrumentRegistrar {

    /**
     * Declare one instrument.
     *
     * @param name        instrument name, unique within the mod
     * @param autoEnable  whether it is on by default
     * @param priority    apply order, lower first
     * @param description one-liner for diagnostics
     * @param body        what the instrument does when applied
     * @return the registered instrument
     */
    Instrument register(String name, boolean autoEnable, int priority, String description,
                        Consumer<InstrumentContext> body);

    /**
     * Declare an automatic instrument with default priority.
     *
     * @param name instrument name
     * @param body what the instrument does when applied
     * @return the registered instrument
     */
    default Instrument register(String name, Consumer<InstrumentContext> body) {
        return register(name, true, 1000, "", body);
    }

    /**
     * Register a pre-built instrument instance.
     *
     * @param instrument the instrument; its {@code modId} must match the declaring mod
     * @return the registered instrument
     */
    Instrument register(Instrument instrument);
}
