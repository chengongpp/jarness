package ch.predmet.jarness.api.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The base directory layout. Everything machine-local lives under one root so that a Jarness
 * installation is a single folder that can be copied, and so that the runtime's own directory is
 * never confused with any agent's working directory.
 *
 * @param baseDir   root, {@code $HOME/.local/share/jarness} by default
 * @param modsDir   {@code <base>/mods}: third-party mod jars
 * @param configDir {@code <base>/config}: runtime config and per-mod config folders
 * @param presetsDir {@code <base>/presets}: preset documents
 */
public record JarnessLayout(Path baseDir, Path modsDir, Path configDir, Path presetsDir) {

    public JarnessLayout {
        Objects.requireNonNull(baseDir, "baseDir");
        baseDir = baseDir.toAbsolutePath().normalize();
        modsDir = baseDir.resolve("mods");
        configDir = baseDir.resolve("config");
        presetsDir = baseDir.resolve("presets");
    }

    /** @return the layout rooted at the default location, {@code $HOME/.local/share/jarness}. */
    public static JarnessLayout defaultLayout() {
        return of(Path.of(System.getProperty("user.home"), ".local", "share", "jarness"));
    }

    /** @return the layout rooted at {@code baseDir}. */
    public static JarnessLayout of(Path baseDir) {
        return new JarnessLayout(baseDir, null, null, null);
    }

    /** @return the runtime configuration document, {@code <config>/jarness.toml}. */
    public Path configFile() {
        return configDir.resolve("jarness.toml");
    }

    /** @return the provider/profile document, {@code <config>/providers.toml}. */
    public Path providersFile() {
        return configDir.resolve("providers.toml");
    }

    /** @return the data directory, {@code <base>/data}. */
    public Path dataDir() {
        return baseDir.resolve("data");
    }

    /** @return the default image store, {@code <base>/data/jarness.db}. */
    public Path imageDb() {
        return dataDir().resolve("jarness.db");
    }

    /** @return the legacy v0.1 path, {@code <base>/sessions.db}; no automatic migration is performed. */
    public Path legacySessionDb() {
        return baseDir.resolve("sessions.db");
    }

    /**
     * @return the SQLite image store, {@code <base>/data/jarness.db}
     * @deprecated use {@link #imageDb()}
     */
    @Deprecated
    public Path sessionDb() {
        return imageDb();
    }

    /** @return the namespace cwd root, {@code <base>/workspaces}. */
    public Path workspacesDir() {
        return baseDir.resolve("workspaces");
    }

    /** @return this mod's configuration folder, {@code <config>/<modId>}. */
    public Path modConfigDir(String modId) {
        return configDir.resolve(modId);
    }

    /** @return this mod's configuration file, {@code <config>/<modId>/<modId>.toml}. */
    public Path modConfigFile(String modId) {
        return modConfigDir(modId).resolve(modId + ".toml");
    }

    /** @return the user preset {@code <name>}, {@code <presets>/<name>.toml}. */
    public Path presetFile(String name) {
        return presetsDir.resolve(name + ".toml");
    }
}
