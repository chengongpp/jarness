package ch.predmet.jarness.core.config;

import ch.predmet.jarness.api.config.ConfigView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * {@link ConfigView} over a Jackson {@link JsonNode} tree.
 *
 * <p>Dotted paths address nested object fields and array indices, e.g. {@code "limits.max_steps"}
 * or {@code "models.0.id"}. Non-scalar nodes (objects, arrays, missing) never surface as
 * strings or numbers.</p>
 */
public final class JsonConfigView implements ConfigView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    /**
     * Wrap one JSON tree.
     *
     * @param root the root node; {@code null} is treated as a missing node
     */
    public JsonConfigView(JsonNode root) {
        this.root = root == null ? MissingNode.getInstance() : root;
    }

    /**
     * Wrap one JSON tree.
     *
     * @param root the root node
     * @return a view over {@code root}
     */
    public static JsonConfigView of(JsonNode root) {
        return new JsonConfigView(root);
    }

    private JsonNode at(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(segment.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                current = index >= 0 && index < current.size() ? current.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    private static boolean isScalar(JsonNode node) {
        return node != null && node.isValueNode() && !node.isNull();
    }

    @Override
    public boolean isEmpty() {
        return keys().isEmpty();
    }

    @Override
    public Set<String> keys() {
        if (!root.isObject()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Override
    public Optional<String> findString(String path) {
        JsonNode node = at(path);
        if (!isScalar(node)) {
            return Optional.empty();
        }
        return Optional.of(node.isTextual() ? node.textValue() : String.valueOf(node.asText()));
    }

    @Override
    public OptionalInt findInt(String path) {
        JsonNode node = at(path);
        if (node == null) {
            return OptionalInt.empty();
        }
        if (node.isIntegralNumber()) {
            return OptionalInt.of(node.intValue());
        }
        if (node.isTextual()) {
            try {
                return OptionalInt.of(Integer.parseInt(node.textValue().trim()));
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public boolean bool(String path, boolean fallback) {
        JsonNode node = at(path);
        if (node == null) {
            return fallback;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            String text = node.textValue().trim();
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }

    @Override
    public List<String> stringList(String path) {
        JsonNode node = at(path);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                out.add(element.isValueNode() ? element.asText() : element.toString());
            }
            return List.copyOf(out);
        }
        if (node.isValueNode()) {
            return List.of(node.isTextual() ? node.textValue() : node.asText());
        }
        return List.of();
    }

    @Override
    public ConfigView table(String path) {
        JsonNode node = at(path);
        if (node == null || !node.isObject()) {
            return ConfigView.empty();
        }
        return new JsonConfigView(node);
    }

    @Override
    public List<ConfigView> tables(String path) {
        JsonNode node = at(path);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<ConfigView> out = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (element.isObject()) {
                out.add(new JsonConfigView(element));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public JsonNode raw() {
        return root;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> shallowMap() {
        if (!root.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = MAPPER.convertValue(root, LinkedHashMap.class);
        return map == null ? Map.of() : map;
    }
}
