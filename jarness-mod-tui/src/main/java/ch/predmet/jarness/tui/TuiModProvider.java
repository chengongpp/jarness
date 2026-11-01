package ch.predmet.jarness.tui;

import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.ModDescriptor;
import ch.predmet.jarness.api.mod.ModProvider;
import ch.predmet.jarness.core.Jarness;

import java.util.List;

/**
 * SPI entry for the TUI system mod. Not required: runtimes without a terminal can ignore it.
 */
public final class TuiModProvider implements ModProvider {

    @Override
    public ModDescriptor descriptor() {
        return new ModDescriptor(TuiMod.ID, "Jarness TUI", Jarness.VERSION, Jarness.API_VERSION,
                "Interactive terminal UI", TuiMod.class.getName(), List.of(), true, false);
    }

    @Override
    public JarnessMod mod() {
        return new TuiMod();
    }
}
