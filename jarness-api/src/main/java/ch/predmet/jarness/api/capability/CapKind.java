package ch.predmet.jarness.api.capability;

/**
 * The kinds of authority an agent can hold.
 *
 * <p>Capabilities are the runtime's answer to "what may this agent touch": the {@link #resource}
 * on a {@link Capability} narrows a kind to a scope (a path prefix, a domain suffix, a model id),
 * and the execution side interprets that scope. Core only defines, attenuates, and revokes.</p>
 */
public enum CapKind {

    /** Read files below a resource scope. */
    FS_READ("fs.read"),
    /** Write, create, or delete files below a resource scope. */
    FS_WRITE("fs.write"),
    /** Spawn local processes. */
    EXEC("exec"),
    /** Reach network endpoints matching a resource scope. */
    NET("net"),
    /** Call model providers. */
    MODEL("model"),
    /** Create child agents. */
    SPAWN("spawn"),
    /** Load mods, register templates, and otherwise extend the running runtime. */
    EXTEND("extend"),
    /** Use collaboration primitives: blackboards and channels. */
    IPC("ipc"),
    /** Cross namespace boundaries. */
    NAMESPACE("namespace"),
    /** Platform-level operations reserved for the super agent. */
    PLATFORM("platform");

    private final String id;

    CapKind(String id) {
        this.id = id;
    }

    /** @return the configuration spelling of this kind, e.g. {@code fs.read}. */
    public String id() {
        return id;
    }

    /**
     * Parse a configured kind.
     *
     * @param value the configured text, e.g. {@code fs_read} or {@code fs.read}
     * @return the kind, or {@code null} when unrecognized
     */
    public static CapKind parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase().replace('_', '.').replace('-', '.');
        for (CapKind kind : values()) {
            if (kind.id.equals(normalized)) {
                return kind;
            }
        }
        return null;
    }
}
