package ch.predmet.jarness.api.mod;

import java.util.List;

/**
 * Static identity and dependency metadata of one mod, before any code runs.
 *
 * <p>For jars discovered in the mods folder this is parsed from {@code META-INF/jnmods.toml}.
 * For system mods already on the classpath it is supplied by the {@link ModProvider} SPI, so that
 * several system mods can coexist inside one shaded jar without colliding on a resource name.</p>
 *
 * @param id           stable mod id, unique across the runtime
 * @param name         human readable name
 * @param version      mod version
 * @param description  optional one-liner
 * @param entrypoint   fully qualified {@link JarnessMod} implementation class
 * @param dependencies declared dependencies
 * @param system       whether the mod ships on the classpath rather than in the mods folder
 * @param required     whether the runtime refuses to start without this mod (core mods set this)
 */
public record ModDescriptor(
        String id,
        String name,
        String version,
        String description,
        String entrypoint,
        List<ModDependency> dependencies,
        boolean system,
        boolean required) {

    public ModDescriptor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("mod id must not be blank");
        }
        name = name == null || name.isBlank() ? id : name;
        version = version == null || version.isBlank() ? "0.0.0" : version;
        description = description == null ? "" : description;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /** @return a classpath mod descriptor that the runtime insists on loading. */
    public static ModDescriptor system(String id, String name, String version, String entrypoint) {
        return new ModDescriptor(id, name, version, "", entrypoint, List.of(), true, true);
    }
}
