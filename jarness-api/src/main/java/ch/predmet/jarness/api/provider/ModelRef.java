package ch.predmet.jarness.api.provider;

/**
 * A provider profile id plus a model id: the pair a request is bound to.
 *
 * @param providerId profile id, e.g. {@code GB10}
 * @param modelId    model id, e.g. {@code DeepSeek-V4-Flash}
 */
public record ModelRef(String providerId, String modelId) {

    public ModelRef {
        providerId = providerId == null ? "" : providerId.trim();
        modelId = modelId == null ? "" : modelId.trim();
    }

    /** @return a reference with an empty provider, resolved against the default profile. */
    public static ModelRef of(String modelId) {
        return new ModelRef("", modelId);
    }

    /**
     * Parse {@code provider/model} or a bare model id.
     *
     * @param selector the selector text
     * @return the reference
     */
    public static ModelRef parse(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("model selector must not be blank");
        }
        int slash = selector.indexOf('/');
        if (slash < 0) {
            return of(selector.trim());
        }
        return new ModelRef(selector.substring(0, slash), selector.substring(slash + 1));
    }

    @Override
    public String toString() {
        return providerId.isEmpty() ? modelId : providerId + "/" + modelId;
    }
}
