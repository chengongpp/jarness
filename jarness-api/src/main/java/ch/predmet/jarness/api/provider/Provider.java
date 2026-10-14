package ch.predmet.jarness.api.provider;

import java.util.List;

/**
 * A model endpoint adapter.
 *
 * <p>One provider instance serves one configured profile: it knows the base URL, the credentials,
 * and the models that profile advertises. Adding a wire protocol means adding an implementation,
 * not touching the agent loop.</p>
 */
public interface Provider extends AutoCloseable {

    /** @return the profile id, matching {@link ModelRef#providerId()}. */
    String id();

    /** @return the wire protocol family. */
    ProviderKind kind();

    /** @return the configured profile. */
    ProviderProfile profile();

    /** @return the models this profile advertises. */
    List<ModelInfo> models();

    /**
     * Run one completion.
     *
     * @param request the assembled request
     * @return the normalized response
     * @throws ProviderException when the endpoint fails or answers unusably
     */
    ChatResponse complete(ChatRequest request);

    /**
     * Run one completion, reporting deltas to {@code sink}.
     *
     * @param request the assembled request
     * @param sink    streaming callbacks; may be {@link StreamSink#DISCARD}
     * @return the normalized response, assembled from the same stream
     * @throws ProviderException when the endpoint fails or answers unusably
     */
    ChatResponse complete(ChatRequest request, StreamSink sink);

    /** @return whether this provider keeps a connection pool that must be closed. */
    @Override
    default void close() {
    }
}
