package ch.predmet.jarness.api.approval;

/** The answer to one approval request. */
public enum ApprovalDecision {

    /** The tool may run. */
    ALLOW,
    /** The tool must not run. */
    DENY
}
