package ch.predmet.jarness.api.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One model-requested tool call, with arguments already parsed from JSON.
 *
 * <p>Accessors are lenient about types because models routinely send {@code "5"} where a number was
 * declared; a tool that cares can demand a strict value through the {@code require*} methods.</p>
 *
 * @param id             provider call id, echoed back with the observation
 * @param name           tool name
 * @param arguments      parsed arguments
 * @param rawArguments   the exact JSON text the model produced
 */
public record ToolInvocation(String id, String name, Map<String, Object> arguments, String rawArguments) {

    public ToolInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        rawArguments = rawArguments == null ? "{}" : rawArguments;
    }

    /** @return the argument value, or {@code null}. */
    public Object get(String key) {
        return arguments.get(key);
    }

    /** @return the argument as text, if present and non-null. */
    public Optional<String> findString(String key) {
        Object value = arguments.get(key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    /**
     * @param key the argument name
     * @return the argument text
     * @throws ToolException when the argument is missing or blank
     */
    public String requireString(String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new ToolException("missing required parameter \"" + key + "\"");
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            throw new ToolException("parameter \"" + key + "\" must not be blank");
        }
        return text;
    }

    /** @return the argument as text, or {@code fallback}. */
    public String string(String key, String fallback) {
        return findString(key).orElse(fallback);
    }

    /** @return the argument as an int, or {@code fallback} when absent or unparsable. */
    public int integer(String key, int fallback) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** @return the argument as a boolean, or {@code fallback} when absent. */
    public boolean bool(String key, boolean fallback) {
        Object value = arguments.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    /** @return the argument as a list of strings, empty when absent. */
    @SuppressWarnings("unchecked")
    public List<String> stringList(String key) {
        Object value = arguments.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value == null) {
            return List.of();
        }
        return List.of(String.valueOf(value));
    }

    /** @return the argument as a nested map, or an empty map when absent or of another shape. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> table(String key) {
        Object value = arguments.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
