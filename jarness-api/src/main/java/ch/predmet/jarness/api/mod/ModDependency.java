package ch.predmet.jarness.api.mod;

import java.util.Objects;

/**
 * One declared dependency of a mod.
 *
 * @param modId        the dependency's mod id
 * @param versionRange a Maven-ish version range ({@code *}, {@code 1.2.3}, {@code [1.0,2.0)}, {@code 1.2.x});
 *                     {@code *} accepts any version
 * @param kind         whether the dependency is required, optional, or forbidden
 * @param ordering     requested relative load order
 * @param reason       optional human explanation surfaced in load diagnostics
 */
public record ModDependency(String modId, String versionRange, DependencyKind kind, ModOrder ordering, String reason) {

    public ModDependency {
        Objects.requireNonNull(modId, "modId");
        versionRange = versionRange == null || versionRange.isBlank() ? "*" : versionRange.trim();
        kind = kind == null ? DependencyKind.REQUIRED : kind;
        ordering = ordering == null ? ModOrder.NONE : ordering;
    }

    /** @return a required dependency on any version of {@code modId}. */
    public static ModDependency required(String modId) {
        return new ModDependency(modId, "*", DependencyKind.REQUIRED, ModOrder.NONE, null);
    }

    /** @return an optional dependency used only for load ordering. */
    public static ModDependency optional(String modId) {
        return new ModDependency(modId, "*", DependencyKind.OPTIONAL, ModOrder.NONE, null);
    }
}
