package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.instrument.Instrument;
import ch.predmet.jarness.api.mod.LoadedMod;

/**
 * Payload for {@code MOD_LOADED} and {@code INSTRUMENT_APPLIED}.
 *
 * @param mod        the mod that loaded
 * @param instrument the instrument that applied, or {@code null} for the mod-level event
 */
public record ModEvent(LoadedMod mod, Instrument instrument) {

    /** @return a mod-level event. */
    public static ModEvent of(LoadedMod mod) {
        return new ModEvent(mod, null);
    }

    /** @return an instrument-level event. */
    public static ModEvent of(LoadedMod mod, Instrument instrument) {
        return new ModEvent(mod, instrument);
    }
}
