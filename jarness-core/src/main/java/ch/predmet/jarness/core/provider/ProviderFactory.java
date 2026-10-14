package ch.predmet.jarness.core.provider;

import ch.predmet.jarness.api.provider.Provider;
import ch.predmet.jarness.api.provider.ProviderProfile;

import java.util.Objects;

/**
 * Creates {@link Provider} adapters for configured profiles.
 */
public final class ProviderFactory {

    private ProviderFactory() {
    }

    /**
     * Create the provider adapter matching the profile's {@link ch.predmet.jarness.api.provider.ProviderKind}.
     *
     * @param profile the configured profile
     * @return the provider adapter
     */
    public static Provider create(ProviderProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return switch (profile.kind()) {
            case OPENAI_COMPAT -> new OpenAiCompatProvider(profile);
            case OPENAI_RESPONSES -> new OpenAiResponsesProvider(profile);
            case ANTHROPIC -> new AnthropicProvider(profile);
        };
    }
}
