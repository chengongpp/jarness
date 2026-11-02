package ch.predmet.jarness.api.config;

/**
 * How far a tool may reach outside its namespace cwd.
 *
 * <p>Jarness has no interactive approval prompt of its own by default: a runtime that is embedded as
 * a library must not block on a human. Instead the deployment declares a sandbox mode, and tools
 * enforce it, so an unattended run is bounded by configuration rather than by a question nobody is
 * there to answer.</p>
 */
public enum SandboxMode {

    /** Tools may read but never write, and {@code exec} runs in a read-only posture. */
    READ_ONLY("read-only"),
    /** Writes are confined to the namespace cwd. */
    WORKSPACE_WRITE("workspace-write"),
    /** No restriction; the agent may touch any path the process can. */
    FULL_ACCESS("full-access");

    private final String id;

    SandboxMode(String id) {
        this.id = id;
    }

    /** @return the configuration spelling of this mode. */
    public String id() {
        return id;
    }

    /**
     * Parse a configured mode.
     *
     * @param value the configured text, e.g. {@code workspace_write} or {@code workspace-write}
     * @param fallback mode used when {@code value} is blank or unrecognized
     * @return the parsed mode
     */
    public static SandboxMode parse(String value, SandboxMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase().replace('_', '-');
        for (SandboxMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return mode;
            }
        }
        return fallback;
    }
}
