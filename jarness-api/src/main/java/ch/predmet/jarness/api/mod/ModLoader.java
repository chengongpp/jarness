package ch.predmet.jarness.api.mod;

import ch.predmet.jarness.api.instrument.Instrument;
import ch.predmet.jarness.api.preset.Preset;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The singleton responsible for "baking" jars into a running runtime.
 *
 * <p>It stays alive after the initial bake because Jarness supports dynamic mod loading at runtime:
 * {@link #loadJar(Path)} adds a mod to an already-running runtime (unloading is deliberately not
 * supported). Every mod and instrument that ever loaded is queryable here.</p>
 */
public interface ModLoader extends AutoCloseable {

    /**
     * Discover all mods (classpath system mods plus jars in {@code <base>/mods}), resolve
     * dependencies, instantiate them in load order, and apply the enabled instruments.
     *
     * @param preset the preset selecting mods and instruments, or {@code null} for default behavior
     * @return the load report; on error nothing is applied and the runtime stays unmodified
     */
    LoadReport loadAll(Preset preset);

    /**
     * Load one mod jar into the running runtime (the {@code Full} template's dynamic loading path).
     *
     * @param jar     the mod jar to scan, parse, resolve, and instantiate
     * @param applyNow whether to apply its enabled instruments immediately
     * @return the load report for this single jar
     */
    LoadReport loadJar(Path jar, boolean applyNow);

    /** @return every loaded mod in resolved load order. */
    List<LoadedMod> loaded();

    /** @return every known instrument, applied or not, in apply order. */
    List<Instrument> instruments();

    /** @return the instrument with the exact name, if registered. */
    Optional<Instrument> instrument(String name);

    /** @return whether an instrument is enabled under the active preset. */
    boolean enabled(String instrumentName);

    /** @return the active preset, if any. */
    Optional<Preset> preset();

    /** @return the report of the most recent load pass. */
    LoadReport lastReport();

    /** @return whether the initial {@link #loadAll} pass completed successfully. */
    boolean baked();

    @Override
    void close();
}
