package ch.predmet.jarness.tui;

import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.ModContext;

/**
 * The TUI system mod ({@code jarness.tui}). Its single instrument {@code tui.frontend} is manual:
 * it applies only when a preset (the builtin {@code tui} preset) enables it, and then starts an
 * interactive terminal session against the running runtime.
 */
public final class TuiMod implements JarnessMod {

    /** The TUI mod id. */
    public static final String ID = "jarness.tui";

    @Override
    public void register(ModContext ctx) {
        ctx.instruments().register("tui.frontend", false, 900,
                "Interactive terminal UI against the running runtime.",
                ic -> ic.hooks().on(HookPoint.RUNTIME_START, "jarness.tui:frontend", 100,
                        hookCtx -> Thread.ofVirtual().name("jarness-tui")
                                .start(new TuiRepl(ic.runtime()))));
    }
}
