package ch.predmet.jarness.api.provider;

/** Wire protocol family of a provider profile. */
public enum ProviderKind {

    /** OpenAI-compatible {@code /chat/completions}. */
    OPENAI_COMPAT("openai-compat"),
    /** OpenAI {@code /responses}. */
    OPENAI_RESPONSES("openai-responses"),
    /** Anthropic {@code /v1/messages}. */
    ANTHROPIC("anthropic");

    private final String id;

    ProviderKind(String id) {
        this.id = id;
    }

    /** @return the configuration spelling of this kind. */
    public String id() {
        return id;
    }

    /**
     * Parse a configured kind.
     *
     * @param value configured text, e.g. {@code openai-compat} or {@code openai_compat}
     * @return the parsed kind
     * @throws IllegalArgumentException when the value is unknown
     */
    public static ProviderKind parse(String value) {
        if (value == null || value.isBlank()) {
            return OPENAI_COMPAT;
        }
        String normalized = value.trim().toLowerCase().replace('_', '-');
        for (ProviderKind kind : values()) {
            if (kind.id.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown provider type \"" + value + "\"; expected one of "
                + java.util.Arrays.stream(values()).map(ProviderKind::id).toList());
    }
}
