package ch.predmet.jarness.api.provider;

import ch.predmet.jarness.api.config.ConfigView;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One configured endpoint profile, e.g. the {@code GB10} entry of {@code config/providers.toml}.
 *
 * @param id       profile id used in {@link ModelRef#providerId()}
 * @param name     display name
 * @param kind     wire protocol family
 * @param baseUrl  endpoint base URL, e.g. {@code http://chspark01/v1}
 * @param apiKey   credential sent as a bearer token; may be empty for unauthenticated endpoints
 * @param headers  extra request headers
 * @param models   models this profile advertises
 * @param options  adapter options such as timeouts, kept loosely typed so profiles can carry extras
 * @param raw      the raw configuration table
 */
public record ProviderProfile(
        String id,
        String name,
        ProviderKind kind,
        String baseUrl,
        String apiKey,
        Map<String, String> headers,
        List<ModelInfo> models,
        Map<String, Object> options,
        ConfigView raw) {

    public ProviderProfile {
        name = name == null || name.isBlank() ? id : name;
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        models = models == null ? List.of() : List.copyOf(models);
        options = options == null ? Map.of() : Map.copyOf(options);
        raw = raw == null ? ConfigView.empty() : raw;
    }

    /** @return the model entry with this id, if advertised. */
    public Optional<ModelInfo> model(String modelId) {
        return models.stream().filter(m -> m.id().equals(modelId)).findFirst();
    }

    /** @return the first advertised model id, or an empty string when none is configured. */
    public String firstModelId() {
        return models.isEmpty() ? "" : models.get(0).id();
    }

    /** @return whether credentials are configured. */
    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }
}
