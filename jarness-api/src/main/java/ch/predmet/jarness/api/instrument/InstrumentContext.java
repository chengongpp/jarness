package ch.predmet.jarness.api.instrument;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.hook.HookBus;
import ch.predmet.jarness.api.provider.ProviderRegistry;
import ch.predmet.jarness.api.tool.ToolRegistry;
import org.slf4j.Logger;

/**
 * The registration surface handed to an instrument while it applies.
 *
 * <p>Unlike a purely functional plugin API, this context exposes live registries that the instrument
 * mutates directly. That is intentional: Jarness cares about being able to reach and compose the
 * runtime's capabilities, not about side-effect purity.</p>
 */
public interface InstrumentContext {

    /** @return the running runtime. */
    JarnessRuntime runtime();

    /** @return the tool registry. */
    ToolRegistry tools();

    /** @return the agent template registry. */
    AgentTemplateRegistry templates();

    /** @return the hook bus. */
    HookBus hooks();

    /** @return the provider registry. */
    ProviderRegistry providers();

    /** @return the runtime configuration document. */
    ConfigView runtimeConfig();

    /** @return the owning mod's configuration document. */
    ConfigView modConfig();

    /** @return the owning mod's id. */
    String modId();

    /** @return the owning instrument. */
    Instrument instrument();

    /** @return a logger tagged with the owning mod. */
    Logger log();
}
