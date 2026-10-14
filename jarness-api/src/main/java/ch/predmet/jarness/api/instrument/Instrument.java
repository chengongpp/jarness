package ch.predmet.jarness.api.instrument;

/**
 * One named, orderable, individually switchable adjustment a mod makes to the runtime.
 *
 * <p>Instruments are the unit of mod instrumentation: every change a mod wants to make is wrapped in
 * one, so that presets can enable or disable it by name and so that load diagnostics can tell the
 * user exactly which piece of a mod was active. An instrument typically registers tools, templates,
 * providers, or hooks when {@link #apply(InstrumentContext)} runs.</p>
 *
 * <p>Ordering: instruments apply in ascending {@link #priority()}, ties broken by mod load order and
 * then by name, which makes registration deterministic without demanding a dependency graph between
 * individual instruments.</p>
 */
public interface Instrument {

    /** @return the instrument's name, unique within its mod. */
    String name();

    /** @return the id of the mod that owns this instrument. */
    String modId();

    /** @return whether the instrument is on unless a preset says otherwise. */
    boolean autoEnable();

    /** @return apply order; lower runs earlier. */
    int priority();

    /** @return a one-liner shown by {@code jarness mods --instruments}. */
    default String description() {
        return "";
    }

    /**
     * Perform the instrument's registrations.
     *
     * @param ctx the registration surface of the running runtime
     */
    void apply(InstrumentContext ctx);

    /** @return the fully qualified {@code modId:name} key used by presets and diagnostics. */
    default String key() {
        return modId() + ":" + name();
    }
}
