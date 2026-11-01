package ch.predmet.jarness.api.kernel;

import ch.predmet.jarness.api.agent.AgentState;

import java.util.List;
import java.util.Optional;

/**
 * A read-only snapshot of one node in the supervision tree, with its descendants.
 *
 * @param pid         process id
 * @param parentPid   parent process id, {@code 0} for the supervisor root
 * @param name        display name
 * @param namespaceId namespace the agent is bound to
 * @param state       lifecycle state
 * @param children    child nodes, in spawn order
 */
public record AgentTree(long pid, long parentPid, String name, String namespaceId, AgentState state,
                        List<AgentTree> children) {

    public AgentTree {
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** @return the number of nodes in this subtree, including this one. */
    public int size() {
        int total = 1;
        for (AgentTree child : children) {
            total += child.size();
        }
        return total;
    }

    /** @return the height of this subtree, {@code 1} for a leaf. */
    public int depth() {
        int deepest = 0;
        for (AgentTree child : children) {
            deepest = Math.max(deepest, child.depth());
        }
        return deepest + 1;
    }

    /** @return whether this node has no children. */
    public boolean leaf() {
        return children.isEmpty();
    }

    /** @return the subtree rooted at {@code pid}, if it is inside this tree. */
    public Optional<AgentTree> find(long pid) {
        if (this.pid == pid) {
            return Optional.of(this);
        }
        for (AgentTree child : children) {
            Optional<AgentTree> found = child.find(pid);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
