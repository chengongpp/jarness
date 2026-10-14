package ch.predmet.jarness.core.system;

import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.ModDescriptor;
import ch.predmet.jarness.api.mod.ModProvider;
import ch.predmet.jarness.core.Jarness;

/**
 * SPI entry for the core system mod, discovered through
 * {@code META-INF/services/ch.predmet.jarness.api.mod.ModProvider} so it keeps working when
 * everything is shaded into one jar.
 */
public final class CoreModProvider implements ModProvider {

    @Override
    public ModDescriptor descriptor() {
        return ModDescriptor.system(CoreMod.ID, "Jarness Core", Jarness.VERSION,
                CoreMod.class.getName());
    }

    @Override
    public JarnessMod mod() {
        return new CoreMod();
    }
}
