package ch.predmet.jarness.api.approval;

/** What the runtime does before a tool with side effects runs. */
public enum ApprovalPolicy {

    /** Run the tool; no human in the loop. */
    ALLOW,
    /** Ask a registered {@link ApprovalHandler}; no handler means deny. */
    ASK,
    /** Only run tools named in the allow-list; everything else is blocked. */
    NEVER;

    /**
     * Parse a configured policy.
     *
     * @param value configured text, e.g. {@code ask}
     * @param fallback policy used for blank/unknown input
     * @return the policy
     */
    public static ApprovalPolicy parse(String value, ApprovalPolicy fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
