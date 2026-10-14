package ch.predmet.jarness.core.provider;

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
 * Minimal {@link ConfigView} backed by a Jackson {@link JsonNode}, used for the {@code raw} view
 * of loaded provider profiles. Dotted paths address nested tables; numeric segments index arrays.
 */
final class JsonConfigView implements ConfigView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode node;

    JsonConfigView(JsonNode node) {
        this.node = node == null || node.isNull() ? MissingNode.getInstance() : node;
    }

    @Override
    public boolean isEmpty() {
        return !node.isObject() || node.isEmpty();
    }

    @Override
    public Set<String> keys() {
        if (!node.isObject()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Override
    public Optional<String> findString(String path) {
        JsonNode at = at(path);
        if (at.isTextual()) {
            return Optional.of(at.asText());
        }
        if (at.isNumber() || at.isBoolean()) {
            return Optional.of(at.asText());
        }
        return Optional.empty();
    }

    @Override
    public OptionalInt findInt(String path) {
        JsonNode at = at(path);
        if (at.isNumber()) {
            return OptionalInt.of(at.intValue());
        }
        if (at.isTextual()) {
            try {
                return OptionalInt.of(Integer.parseInt(at.asText().trim()));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public boolean bool(String path, boolean fallback) {
        JsonNode at = at(path);
        if (at.isBoolean()) {
            return at.asBoolean();
        }
        if (at.isTextual()) {
            return Boolean.parseBoolean(at.asText().trim());
        }
        return fallback;
    }

    @Override
    public List<String> stringList(String path) {
        JsonNode at = at(path);
        if (!at.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        at.forEach(element -> values.add(element.isValueNode() ? element.asText() : element.toString()));
        return List.copyOf(values);
    }

    @Override
    public ConfigView table(String path) {
        JsonNode at = at(path);
        return at.isObject() ? new JsonConfigView(at) : ConfigView.empty();
    }

    @Override
    public List<ConfigView> tables(String path) {
        JsonNode at = at(path);
        if (!at.isArray()) {
            return List.of();
        }
        List<ConfigView> tables = new ArrayList<>();
        at.forEach(element -> tables.add(element.isObject() ? new JsonConfigView(element) : ConfigView.empty()));
        return List.copyOf(tables);
    }

    @Override
    public JsonNode raw() {
        return node;
    }

    @Override
    public Map<String, Object> shallowMap() {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(),
                MAPPER.convertValue(entry.getValue(), Object.class)));
        return Map.copyOf(map);
    }

    private JsonNode at(String path) {
        if (path == null || path.isBlank()) {
            return node;
        }
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return MissingNode.getInstance();
            }
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray()) {
                try {
                    current = current.get(Integer.parseInt(segment));
                } catch (NumberFormatException e) {
                    return MissingNode.getInstance();
                }
            } else {
                return MissingNode.getInstance();
            }
        }
        return current == null ? MissingNode.getInstance() : current;
    }
}
