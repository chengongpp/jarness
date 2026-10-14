package ch.predmet.jarness.sample;

import ch.predmet.jarness.api.annotation.Mod;
import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.ModContext;

/**
 * Sample external mod: registers one instrument that contributes a trivial echo tool.
 * Exists to exercise jar discovery, {@code jnmods.toml} parsing, dependency resolution,
 * and dynamic loading.
 */
@Mod("jarness.sample")
public final class SampleMod implements JarnessMod {

    @Override
    public void register(ModContext ctx) {
        ctx.instruments().register("echo-tool", true, 500,
                "Contributes the sample_echo tool.",
                ic -> ic.tools().register(new EchoTool()));
    }
}
