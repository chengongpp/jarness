package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.hook.HookBus;
import ch.predmet.jarness.api.instrument.Instrument;
import ch.predmet.jarness.api.instrument.InstrumentContext;
import ch.predmet.jarness.api.provider.ProviderRegistry;
import ch.predmet.jarness.api.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link InstrumentContext} handed to one instrument while it applies. Registries are resolved
 * lazily against the runtime supplier, so the context can be built before the runtime exists.
 */
public final class InstrumentContextImpl implements InstrumentContext {

    private final Supplier<JarnessRuntime> runtime;
    private final String modId;
    private final Instrument instrument;
    private final ConfigView modConfig;
    private final Logger log;

    /**
     * Create the context for one instrument application.
     *
     * @param runtime    supplies the running runtime
     * @param modId      the owning mod id
     * @param instrument the instrument being applied
     * @param modConfig  the owning mod's configuration view
     */
    public InstrumentContextImpl(Supplier<JarnessRuntime> runtime, String modId, Instrument instrument,
                                 ConfigView modConfig) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.modId = Objects.requireNonNull(modId, "modId");
        this.instrument = Objects.requireNonNull(instrument, "instrument");
        this.modConfig = modConfig == null ? ConfigView.empty() : modConfig;
        this.log = LoggerFactory.getLogger("jarness.mod." + modId);
    }

    @Override
    public JarnessRuntime runtime() {
        return runtime.get();
    }

    @Override
    public ToolRegistry tools() {
        return runtime().tools();
    }

    @Override
    public AgentTemplateRegistry templates() {
        return runtime().templates();
    }

    @Override
    public HookBus hooks() {
        return runtime().hooks();
    }

    @Override
    public ProviderRegistry providers() {
        return runtime().providers();
    }

    @Override
    public ConfigView runtimeConfig() {
        return runtime().config().raw();
    }

    @Override
    public ConfigView modConfig() {
        return modConfig;
    }

    @Override
    public String modId() {
        return modId;
    }

    @Override
    public Instrument instrument() {
        return instrument;
    }

    @Override
    public Logger log() {
        return log;
    }
}
