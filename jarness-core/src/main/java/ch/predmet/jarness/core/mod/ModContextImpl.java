package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.instrument.InstrumentRegistrar;
import ch.predmet.jarness.api.mod.ModContext;
import ch.predmet.jarness.api.mod.ModDescriptor;
import ch.predmet.jarness.core.config.TomlFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link ModContext} scoped to one mod. The mod's own configuration is read from
 * {@code <base>/config/<modId>/<modId>.toml}; the configuration folder is created on demand.
 */
public final class ModContextImpl implements ModContext {

    private final ModDescriptor descriptor;
    private final JarnessLayout layout;
    private final Supplier<JarnessRuntime> runtime;
    private final Path modFile;
    private final InstrumentRegistrarImpl registrar;
    private final Logger log;
    private volatile ConfigView modConfig;

    /**
     * Create the context for one mod.
     *
     * @param descriptor the mod's descriptor
     * @param layout     the base directory layout
     * @param runtime    supplies the running runtime; may yield {@code null} before construction
     * @param modFile    the jar the mod came from, or {@code null} for a system mod
     * @param registrar  the registrar collecting this mod's instruments
     */
    public ModContextImpl(ModDescriptor descriptor, JarnessLayout layout, Supplier<JarnessRuntime> runtime,
                          Path modFile, InstrumentRegistrarImpl registrar) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.modFile = modFile;
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.log = LoggerFactory.getLogger("jarness.mod." + descriptor.id());
    }

    @Override
    public ModDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConfigView runtimeConfig() {
        JarnessRuntime rt = runtime.get();
        return rt != null && rt.config() != null ? rt.config().raw() : ConfigView.empty();
    }

    @Override
    public ConfigView modConfig() {
        ConfigView config = modConfig;
        if (config == null) {
            synchronized (this) {
                config = modConfig;
                if (config == null) {
                    config = TomlFiles.load(layout.modConfigFile(descriptor.id()));
                    modConfig = config;
                }
            }
        }
        return config;
    }

    @Override
    public Path configDir() {
        Path dir = layout.modConfigDir(descriptor.id());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create config directory " + dir, e);
        }
        return dir;
    }

    @Override
    public Path baseDir() {
        return layout.baseDir();
    }

    @Override
    public Path modFile() {
        return modFile;
    }

    @Override
    public InstrumentRegistrar instruments() {
        return registrar;
    }

    @Override
    public Logger log() {
        return log;
    }
}
