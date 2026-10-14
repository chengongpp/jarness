package ch.predmet.jarness.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a mod entrypoint, NeoForge style.
 *
 * <p>Equivalent to declaring the class in {@code META-INF/jnmods.toml} as the entrypoint, and
 * honoured for jars whose descriptor omits the entrypoint: the loader then scans the jar for a
 * single {@code @Mod}-annotated {@link ch.predmet.jarness.api.mod.JarnessMod} implementation.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {

    /** @return the mod id; must match the descriptor id when both are present. */
    String value();

    /** @return human readable name, defaulting to the id. */
    String name() default "";

    /** @return mod version, defaulting to the descriptor version. */
    String version() default "";
}
