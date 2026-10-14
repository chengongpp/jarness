package ch.predmet.jarness.core.provider;

import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.provider.Provider;
import ch.predmet.jarness.api.provider.ProviderProfile;
import ch.predmet.jarness.api.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link ProviderRegistry}: a {@link LinkedHashMap} of providers keyed by profile id plus
 * the default model selection.
 *
 * <p>Registration order is preserved for storage; {@link #all()} honours the API contract and
 * returns providers ordered by id. {@link #unregister(String)} closes the removed provider.</p>
 */
public final class ProviderRegistryImpl implements ProviderRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderRegistryImpl.class);

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private ModelRef defaultModel;

    /** Creates an empty registry. */
    public ProviderRegistryImpl() {
    }

    @Override
    public synchronized void register(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        providers.put(provider.id(), provider);
    }

    @Override
    public synchronized boolean unregister(String id) {
        Provider removed = providers.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            removed.close();
        } catch (RuntimeException e) {
            LOG.warn("provider {} failed to close cleanly", id, e);
        }
        return true;
    }

    @Override
    public synchronized Optional<Provider> byId(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    @Override
    public Provider require(String id) {
        return byId(id).orElseThrow(() -> new IllegalArgumentException(
                "no provider registered with profile id \"" + id + "\"; registered profiles: " + profileIds()));
    }

    @Override
    public synchronized List<Provider> all() {
        List<Provider> all = new ArrayList<>(providers.values());
        all.sort(Comparator.comparing(Provider::id));
        return List.copyOf(all);
    }

    @Override
    public List<ProviderProfile> profiles() {
        return all().stream().map(Provider::profile).toList();
    }

    @Override
    public Optional<ProviderProfile> profile(String id) {
        return byId(id).map(Provider::profile);
    }

    @Override
    public List<ModelRef> modelRefs() {
        List<ModelRef> refs = new ArrayList<>();
        for (Provider provider : all()) {
            for (var model : provider.models()) {
                refs.add(new ModelRef(provider.id(), model.id()));
            }
        }
        return List.copyOf(refs);
    }

    @Override
    public synchronized Optional<ModelRef> defaultModel() {
        return Optional.ofNullable(defaultModel);
    }

    @Override
    public synchronized void setDefault(ModelRef ref) {
        this.defaultModel = ref;
    }

    @Override
    public ModelRef resolve(ModelRef requested) {
        if (requested == null || requested.modelId().isBlank()) {
            throw new IllegalArgumentException("model id must not be blank");
        }
        String providerId = requested.providerId().isBlank()
                ? defaultProviderId()
                : requested.providerId();
        final String resolvedId = providerId;
        Provider provider = byId(providerId).orElseThrow(() -> new IllegalArgumentException(
                "unknown provider profile \"" + resolvedId + "\"; registered profiles: " + profileIds()));
        List<String> advertised = provider.profile().models().stream().map(m -> m.id()).toList();
        if (!advertised.isEmpty() && !advertised.contains(requested.modelId())) {
            throw new IllegalArgumentException("profile \"" + providerId + "\" does not advertise model \""
                    + requested.modelId() + "\"; advertised models: " + advertised
                    + " (use one of these, or remove the models list from the profile to allow any id)");
        }
        return new ModelRef(providerId, requested.modelId());
    }

    @Override
    public ModelRef resolveSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return defaultModel().orElseThrow(() -> new IllegalArgumentException(
                    "no model selected and no default model configured; configure [profiles] in providers.toml"));
        }
        return resolve(ModelRef.parse(selector));
    }

    @Override
    public Provider providerFor(ModelRef ref) {
        Objects.requireNonNull(ref, "ref");
        return byId(ref.providerId()).orElseThrow(() -> new IllegalArgumentException(
                "no provider registered with profile id \"" + ref.providerId()
                        + "\"; registered profiles: " + profileIds()));
    }

    private String defaultProviderId() {
        synchronized (this) {
            if (defaultModel != null && !defaultModel.providerId().isBlank()) {
                return defaultModel.providerId();
            }
            if (providers.size() == 1) {
                return providers.keySet().iterator().next();
            }
            throw new IllegalArgumentException(
                    "no provider selected and no default model configured; registered profiles: " + profileIds()
                            + " (use a provider/model selector or set a default model)");
        }
    }

    private synchronized List<String> profileIds() {
        return List.copyOf(providers.keySet());
    }
}
