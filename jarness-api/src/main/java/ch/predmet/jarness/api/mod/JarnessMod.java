package ch.predmet.jarness.api.mod;

/**
 * Code half of a mod: the object the loader instantiates and then hands a {@link ModContext}.
 *
 * <p>Jarness deliberately does not require functional purity. A mod may hold state, mutate the
 * runtime, open sockets, and keep references it needs; the runtime only requires that everything a
 * mod *registers* flows through the mod's {@link ModContext} so that loading, presets, and
 * diagnostics can see it.</p>
 */
public interface JarnessMod {

    /**
     * Register this mod's contribution: instruments, tools, templates, providers, and hooks.
     * Called exactly once, after dependency resolution ordered this mod.
     *
     * @param ctx the mod's registration context; never {@code null}
     */
    void register(ModContext ctx);
}
