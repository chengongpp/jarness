package ch.predmet.jarness.api.config.spi;

import ch.predmet.jarness.api.config.ConfigView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** The singleton empty {@link ConfigView}, kept in the API so absent configuration needs no loader. */
public final class EmptyConfigView implements ConfigView {

    /** Shared instance. */
    public static final EmptyConfigView INSTANCE = new EmptyConfigView();

    private EmptyConfigView() {
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Set<String> keys() {
        return Set.of();
    }

    @Override
    public Optional<String> findString(String path) {
        return Optional.empty();
    }

    @Override
    public OptionalInt findInt(String path) {
        return OptionalInt.empty();
    }

    @Override
    public boolean bool(String path, boolean fallback) {
        return fallback;
    }

    @Override
    public List<String> stringList(String path) {
        return List.of();
    }

    @Override
    public ConfigView table(String path) {
        return this;
    }

    @Override
    public List<ConfigView> tables(String path) {
        return List.of();
    }

    @Override
    public JsonNode raw() {
        return MissingNode.getInstance();
    }

    @Override
    public Map<String, Object> shallowMap() {
        return Map.of();
    }
}
