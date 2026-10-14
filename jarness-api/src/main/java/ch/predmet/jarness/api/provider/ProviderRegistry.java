package ch.predmet.jarness.api.provider;

import java.util.List;
import java.util.Optional;

/**
 * The runtime's model endpoint table plus the default model selection.
 */
public interface ProviderRegistry {

    /**
     * Register or replace a provider.
     *
     * @param provider the provider to register
     */
    void register(Provider provider);

    /**
     * Remove a provider.
     *
     * @param id profile id
     * @return whether a provider was removed
     */
    boolean unregister(String id);

    /** @return the provider with this profile id, if registered. */
    Optional<Provider> byId(String id);

    /**
     * @param id profile id
     * @return the provider
     * @throws IllegalArgumentException when no such provider is registered
     */
    Provider require(String id);

    /** @return every registered provider, ordered by id. */
    List<Provider> all();

    /** @return every registered profile. */
    List<ProviderProfile> profiles();

    /** @return the profile with this id, if registered. */
    Optional<ProviderProfile> profile(String id);

    /** @return every advertised model across profiles, as {@code provider/model} refs. */
    List<ModelRef> modelRefs();

    /** @return the model used when nothing else selects one. */
    Optional<ModelRef> defaultModel();

    /**
     * Set the fallback model.
     *
     * @param ref the model reference
     */
    void setDefault(ModelRef ref);

    /**
     * Complete a partially specified reference: fill in a missing provider from the default, and
     * reject a model the target profile does not advertise with an actionable message.
     *
     * @param requested the requested reference; the model id must not be blank
     * @return the resolved reference
     * @throws IllegalArgumentException when the reference cannot be resolved
     */
    ModelRef resolve(ModelRef requested);

    /**
     * Resolve a {@code provider/model}, {@code model}, or preset-supplied selector string.
     *
     * @param selector the selector text, possibly {@code null}
     * @return the resolved reference, or the default when {@code selector} is blank
     * @throws IllegalArgumentException when nothing is configured and no default exists
     */
    ModelRef resolveSelector(String selector);

    /**
     * Find the provider serving a reference.
     *
     * @param ref a resolved reference
     * @return the provider
     * @throws IllegalArgumentException when no provider matches
     */
    Provider providerFor(ModelRef ref);
}
