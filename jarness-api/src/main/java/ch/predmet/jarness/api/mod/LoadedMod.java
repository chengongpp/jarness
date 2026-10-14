package ch.predmet.jarness.api.mod;

import java.nio.file.Path;

/**
 * One mod that finished loading, with the load order it was resolved into.
 *
 * @param descriptor static metadata
 * @param file       the jar it came from, or {@code null} for a classpath/system mod
 * @param order      0-based position in the resolved load order
 */
public record LoadedMod(ModDescriptor descriptor, Path file, int order) {
}
