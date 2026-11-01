package ch.predmet.jarness.api.kernel;

/**
 * What happens to children when their parent dies.
 *
 * <p>{@code KILL} is the implemented policy: the supervisor kills the whole subtree. {@code ADOPT}
 * and {@code DETACH} are declared so manifests can be written against the final model, but the
 * supervisor rejects them instead of pretending to honor them.</p>
 */
public enum OrphanPolicy {

    /** Kill the subtree when the parent exits. */
    KILL,
    /** Re-parent children to the supervisor. Not implemented yet. */
    ADOPT,
    /** Let children keep running detached. Not implemented yet. */
    DETACH
}
