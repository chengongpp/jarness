package ch.predmet.jarness.api.kernel;

/**
 * Thrown when the supervisor refuses to create an agent.
 *
 * <p>A refusal is not an internal error: it is the normal answer to "spawn deeper than allowed",
 * "spawn wider than allowed", "spend more budget than the parent has left", "cross a namespace
 * without the capability", or "instantiate a template that does not exist". Every refusal is also
 * emitted as an event, so observers see denied spawns as well as successful ones.</p>
 */
public final class SpawnDeniedException extends RuntimeException {

    /** Why a spawn was refused. */
    public enum Reason {
        /** The parent pid is unknown. */
        PARENT,
        /** The namespace does not exist, or was not authorized from the parent's namespace. */
        NAMESPACE,
        /** The tree depth limit would be exceeded. */
        DEPTH,
        /** The parent already has the maximum number of children. */
        CHILDREN,
        /** The requested budget does not fit within the parent's remaining budget. */
        BUDGET,
        /** The parent does not hold the capability the spawn needs, or the policy forbids it. */
        CAPABILITY,
        /** The template is missing or its version does not match. */
        TEMPLATE,
        /** The requested model profile does not exist in the namespace. */
        MODEL,
        /** The image is already owned by a live agent; every image has exactly one writer. */
        IMAGE,
        /** The supervisor is not running. */
        NOT_RUNNING
    }

    private final Reason reason;
    private final long parentPid;
    private final String namespaceId;

    /**
     * @param reason      why the spawn was refused
     * @param message     human-readable explanation
     * @param parentPid   the parent that asked
     * @param namespaceId the namespace that was targeted
     */
    public SpawnDeniedException(Reason reason, String message, long parentPid, String namespaceId) {
        super(message);
        this.reason = reason;
        this.parentPid = parentPid;
        this.namespaceId = namespaceId;
    }

    /** @return the refusal category. */
    public Reason reason() {
        return reason;
    }

    /** @return the parent that asked. */
    public long parentPid() {
        return parentPid;
    }

    /** @return the targeted namespace. */
    public String namespaceId() {
        return namespaceId;
    }
}
