package ch.predmet.jarness.api.mod;

import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.instrument.InstrumentRegistrar;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Everything one mod is allowed to touch while registering, scoped to that mod.
 *
 * @see JarnessMod#register(ModContext)
 */
public interface ModContext {

    /** @return this mod's descriptor. */
    ModDescriptor descriptor();

    /** @return this mod's id, a shorthand for {@code descriptor().id()}. */
    default String modId() {
        return descriptor().id();
    }

    /** @return the runtime's configuration view. */
    ConfigView runtimeConfig();

    /**
     * Load this mod's own configuration from {@code <base>/config/<modId>/<modId>.toml}.
     *
     * @return the mod configuration view; empty when the file is absent
     */
    ConfigView modConfig();

    /** @return the configuration folder for this mod, created on demand. */
    Path configDir();

    /** @return the runtime base directory (holds {@code mods/}, {@code config/}, {@code presets/}). */
    Path baseDir();

    /** @return the jar this mod was loaded from, or {@code null} for classpath/system mods. */
    Path modFile();

    /** @return this mod's instrument registrar. */
    InstrumentRegistrar instruments();

    /** @return a logger already tagged with this mod's id. */
    Logger log();
}
