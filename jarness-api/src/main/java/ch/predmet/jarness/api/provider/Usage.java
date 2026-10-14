package ch.predmet.jarness.api.provider;

/**
 * Token accounting for one completion.
 *
 * @param promptTokens     tokens sent
 * @param completionTokens tokens generated
 * @param cachedTokens     prompt tokens served from cache, when the provider reports it
 */
public record Usage(int promptTokens, int completionTokens, int cachedTokens) {

    /** No usage reported. */
    public static final Usage EMPTY = new Usage(0, 0, 0);

    /** @return the total tokens. */
    public int total() {
        return promptTokens + completionTokens;
    }

    /** @return this usage plus {@code other}. */
    public Usage plus(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(promptTokens + other.promptTokens(),
                completionTokens + other.completionTokens(),
                cachedTokens + other.cachedTokens());
    }
}
