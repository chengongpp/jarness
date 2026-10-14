package ch.predmet.jarness.api.mod;

/**
 * Service-provider interface for system mods that live directly on the classpath.
 *
 * <p>Declared in {@code META-INF/services/ch.predmet.jarness.api.mod.ModProvider}. Using an SPI
 * instead of a single {@code META-INF/jnmods.toml} resource keeps several system mods loadable from
 * one shaded jar, where resource files with the same name would otherwise overwrite each other.</p>
 */
public interface ModProvider {

    /** @return static metadata of the provided mod. */
    ModDescriptor descriptor();

    /**
     * Create the mod instance.
     *
     * @return a fresh {@link JarnessMod}; the loader calls it once
     */
    JarnessMod mod();
}
