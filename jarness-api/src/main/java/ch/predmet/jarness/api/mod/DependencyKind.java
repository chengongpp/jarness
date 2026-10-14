package ch.predmet.jarness.api.mod;

/** Whether a declared mod dependency must resolve, may resolve, or must not resolve. */
public enum DependencyKind {
    /** The dependency must be present with a matching version, otherwise loading fails. */
    REQUIRED,
    /** The dependency is used only to establish load ordering and may be absent. */
    OPTIONAL,
    /** The dependency must be absent; its presence is a hard load error. */
    INCOMPATIBLE
}
