package ch.predmet.jarness.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an instrument from a method instead of from imperative code.
 *
 * <p>The annotated method must be an instance method on the {@link ch.predmet.jarness.api.mod.JarnessMod}
 * implementation, take a single {@link ch.predmet.jarness.api.instrument.InstrumentContext} parameter,
 * and return {@code void}. The loader turns each such method into a named, orderable, switchable
 * instrument, which keeps the common case declarative while {@code ModContext.instruments()} remains
 * available for instruments that need to be built at runtime.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Instrument {

    /** @return instrument name, unique within the mod. */
    String value();

    /** @return whether the instrument applies without an explicit preset opt-in. */
    boolean autoEnable() default true;

    /** @return apply order, lower runs earlier. */
    int priority() default 1000;

    /** @return one-liner shown by {@code jarness mods --instruments}. */
    String description() default "";
}
