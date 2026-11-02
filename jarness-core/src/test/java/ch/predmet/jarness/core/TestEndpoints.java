package ch.predmet.jarness.core;

import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.provider.ModelInfo;
import ch.predmet.jarness.api.provider.ProviderKind;
import ch.predmet.jarness.api.provider.ProviderProfile;
import ch.predmet.jarness.core.config.ConfigLoader;
import ch.predmet.jarness.core.provider.ProfileLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test support: resolves the live endpoint from the installed runtime's {@code providers.toml},
 * falling back to {@code tmp/endpoint.txt} (a JSON-ish fragment with a trailing comma) for
 * standalone checkouts.
 */
public final class TestEndpoints {

    private TestEndpoints() {
    }

    /**
     * Resolve the live endpoint, preferring the installed runtime's {@code providers.toml} so tests
     * follow the same endpoint the daemon uses. Falls back to {@code tmp/endpoint.txt}.
     *
     * @return the profile to test against
     */
    public static ProviderProfile gb10() {
        JarnessLayout installed = JarnessLayout.defaultLayout();
        if (Files.isRegularFile(installed.providersFile())) {
            List<ProviderProfile> profiles = ProfileLoader.load(installed.providersFile());
            if (!profiles.isEmpty()) {
                String preferred = ConfigLoader.load(installed).defaultProvider();
                return profiles.stream()
                        .filter(profile -> profile.id().equals(preferred))
                        .findFirst()
                        .orElse(profiles.get(0));
            }
        }
        Path file = Path.of(System.getProperty("jarness.endpointFile", "tmp/endpoint.txt"));
        if (!Files.isRegularFile(file)) {
            // surefire runs with the module dir as working directory; the file lives at the root
            file = Path.of("..", "tmp", "endpoint.txt");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("endpoint file not found: " + file.toAbsolutePath());
        }
        try {
            String text = Files.readString(file).trim();
            // The file is a fragment like `"GB10": { ... },` — wrap it into an object.
            if (text.endsWith(",")) {
                text = text.substring(0, text.length() - 1);
            }
            if (!text.startsWith("{")) {
                text = "{" + text + "}";
            }
            JsonNode root = new ObjectMapper().readTree(text);
            String id = root.fieldNames().next();
            JsonNode node = root.get(id);
            List<ModelInfo> models = new ArrayList<>();
            for (JsonNode model : node.path("models")) {
                models.add(new ModelInfo(
                        model.path("id").asText(),
                        model.path("name").asText(""),
                        model.path("context_window").asInt(0),
                        model.path("default_max_tokens").asInt(0),
                        true));
            }
            return new ProviderProfile(
                    id,
                    node.path("name").asText(id),
                    ProviderKind.parse(node.path("type").asText("openai-compat")),
                    node.path("base_url").asText(),
                    node.path("api_key").asText(""),
                    Map.of(),
                    models,
                    Map.of(),
                    null);
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse " + file, e);
        }
    }
}
