package ch.predmet.jarness.api.kernel;

/**
 * A reference to a checkpointed agent image.
 *
 * <p>In the single-node runtime the reference is the session id plus the namespace; the checkpoint
 * turn is carried so a caller can tell which version of the image was captured.</p>
 *
 * @param sessionId      session/image id
 * @param namespaceId    namespace the image is bound to
 * @param checkpointTurn last committed turn covered by the checkpoint, or {@code -1} for "latest"
 */
public record ImageRef(String sessionId, String namespaceId, int checkpointTurn) {

    public ImageRef {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        namespaceId = namespaceId == null || namespaceId.isBlank() ? "default" : namespaceId;
    }
}
