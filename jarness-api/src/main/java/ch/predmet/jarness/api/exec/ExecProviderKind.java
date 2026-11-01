package ch.predmet.jarness.api.exec;

/** How an execution provider reaches the code it runs. */
public enum ExecProviderKind {

    /** A provider that talks to an executor on the same machine. */
    LOCAL,
    /** A provider that talks to a remote executor over an authenticated connection. */
    REMOTE
}
