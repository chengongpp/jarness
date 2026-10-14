package ch.predmet.jarness.core.provider;

import ch.predmet.jarness.api.provider.ModelInfo;
import ch.predmet.jarness.api.provider.ProviderKind;
import ch.predmet.jarness.api.provider.ProviderProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@link ProviderProfile}s from {@code providers.toml}.
 *
 * <p>Expected layout: one {@code [profiles.<id>]} table per profile with {@code name},
 * {@code type}, {@code base_url}, {@code api_key}, an optional {@code headers} sub-table and an
 * optional {@code [[profiles.<id>.models]]} array of tables. Any other key is kept as a loosely
 * typed adapter option.</p>
 */
public final class ProfileLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileLoader.class);

    /** Keys mapped onto {@link ProviderProfile} components; everything else lands in options. */
    private static final Set<String> MAPPED_KEYS =
            Set.of("name", "type", "base_url", "api_key", "headers", "models");

    private ProfileLoader() {
    }

    /**
     * Load all profiles from a {@code providers.toml} file.
     *
     * @param providersToml path to the TOML document; a missing file yields an empty list
     * @return the parsed profiles, in file order
     * @throws IllegalStateException when the file cannot be parsed or a profile is malformed
     */
    public static List<ProviderProfile> load(Path providersToml) {
        if (providersToml == null || !Files.exists(providersToml)) {
            LOG.debug("no providers.toml at {}; starting with no profiles", providersToml);
            return List.of();
        }
        ObjectMapper mapper = new TomlMapper();
        JsonNode root;
        try {
            root = mapper.readTree(Files.readAllBytes(providersToml));
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse providers file " + providersToml + ": " + e.getMessage(), e);
        }
        JsonNode profiles = root == null ? null : root.get("profiles");
        if (profiles == null || !profiles.isObject()) {
            return List.of();
        }
        List<ProviderProfile> loaded = new ArrayList<>();
        profiles.fields().forEachRemaining(entry ->
                loaded.add(profile(entry.getKey(), entry.getValue(), providersToml, mapper)));
        return List.copyOf(loaded);
    }

    private static ProviderProfile profile(String id, JsonNode node, Path source, ObjectMapper mapper) {
        if (!node.isObject()) {
            throw new IllegalStateException("profile \"" + id + "\" in " + source + " must be a table");
        }
        String name = text(node, "name", id);
        ProviderKind kind;
        try {
            kind = ProviderKind.parse(text(node, "type", null));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("profile \"" + id + "\": " + e.getMessage(), e);
        }
        String baseUrl = text(node, "base_url", "");
        String apiKey = text(node, "api_key", "");
        Map<String, String> headers = headers(id, node.get("headers"), source);
        List<ModelInfo> models = models(id, node.get("models"), source);
        Map<String, Object> options = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!MAPPED_KEYS.contains(entry.getKey())) {
                options.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
            }
        });
        return new ProviderProfile(id, name, kind, baseUrl, apiKey, headers, models, options,
                new JsonConfigView(node));
    }

    private static Map<String, String> headers(String id, JsonNode node, Path source) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalStateException("profile \"" + id + "\" in " + source + ": headers must be a table");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(headers);
    }

    private static List<ModelInfo> models(String id, JsonNode node, Path source) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalStateException("profile \"" + id + "\" in " + source + ": models must be an array of tables");
        }
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode model : node) {
            String modelId = text(model, "id", "");
            if (modelId.isBlank()) {
                throw new IllegalStateException("profile \"" + id + "\" in " + source + ": every model needs an id");
            }
            models.add(new ModelInfo(
                    modelId,
                    text(model, "name", modelId),
                    number(model, "context_window", 0),
                    number(model, "default_max_tokens", 0),
                    !model.hasNonNull("supports_tools") || model.get("supports_tools").asBoolean()));
        }
        return List.copyOf(models);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() ? value.asText() : fallback;
    }

    private static int number(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.intValue() : fallback;
    }
}
