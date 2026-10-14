package ch.predmet.jarness.api.provider;

/**
 * One model advertised by a provider profile.
 *
 * @param id               model id sent on the wire
 * @param name             display name
 * @param contextWindow    advertised context window in tokens, or 0 when unknown
 * @param defaultMaxTokens default completion budget, or 0 when unknown
 * @param supportsTools    whether the model accepts tool schemas
 */
public record ModelInfo(String id, String name, int contextWindow, int defaultMaxTokens, boolean supportsTools) {

    public ModelInfo {
        name = name == null || name.isBlank() ? id : name;
    }

    /** @return a tool-capable model entry. */
    public static ModelInfo of(String id, String name, int contextWindow, int defaultMaxTokens) {
        return new ModelInfo(id, name, contextWindow, defaultMaxTokens, true);
    }
}
