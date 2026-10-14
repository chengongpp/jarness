package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.mod.DependencyKind;
import ch.predmet.jarness.api.mod.ModDependency;
import ch.predmet.jarness.api.mod.ModDescriptor;
import ch.predmet.jarness.api.mod.ModLoadError;
import ch.predmet.jarness.api.mod.ModOrder;
import ch.predmet.jarness.api.util.Version;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Deterministic dependency resolution for mod descriptors, FancyModLoader-flavored.
 *
 * <p>Rules: duplicate ids, missing or version-mismatched required dependencies, and present
 * incompatible dependencies are errors (phase {@code RESOLVE}). Required/optional dependencies
 * imply "after that mod" load edges; explicit {@code before}/{@code after} add edges. The
 * topological order is computed with Kahn's algorithm, ties broken by system mods first and then
 * by mod id, so the result is fully deterministic. Cycles are reported as a single error naming
 * the cycle path.</p>
 */
public final class ModResolver {

    private ModResolver() {
    }

    /**
     * One mod candidate: its descriptor plus the jar it came from.
     *
     * @param descriptor static metadata
     * @param file       the source jar, or {@code null} for a classpath/system mod
     */
    public record Candidate(ModDescriptor descriptor, Path file) {

        public Candidate {
            Objects.requireNonNull(descriptor, "descriptor");
        }

        private String source() {
            return file != null ? file.toString() : "classpath";
        }
    }

    /**
     * Resolution outcome: either the deterministic load order or the errors that prevented it.
     *
     * @param order  resolved load order; empty when {@link #errors} is non-empty
     * @param errors resolution problems, all in phase {@code RESOLVE}
     */
    public record Result(List<Candidate> order, List<ModLoadError> errors) {

        public Result {
            order = order == null ? List.of() : List.copyOf(order);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** @return whether resolution succeeded. */
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Resolve a set of candidate mods into a deterministic load order.
     *
     * @param candidates the discovered mods
     * @return the load order, or the errors that made resolution impossible
     */
    public static Result resolve(Collection<Candidate> candidates) {
        List<ModLoadError> errors = new ArrayList<>();
        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String id = candidate.descriptor().id();
            Candidate previous = byId.putIfAbsent(id, candidate);
            if (previous != null) {
                errors.add(new ModLoadError("<runtime>", candidate.file(), "RESOLVE",
                        "duplicate mod id '" + id + "' provided by " + previous.source()
                                + " and " + candidate.source(),
                        "", "remove one of the two mods"));
            }
        }
        if (!errors.isEmpty()) {
            return new Result(List.of(), errors);
        }

        // adjacency: from -> set of mods that must load after 'from'
        Map<String, Set<String>> after = new LinkedHashMap<>();
        byId.keySet().forEach(id -> after.put(id, new LinkedHashSet<>()));

        for (Candidate candidate : byId.values()) {
            ModDescriptor descriptor = candidate.descriptor();
            for (ModDependency dependency : descriptor.dependencies()) {
                String depId = dependency.modId();
                if (depId.equals(descriptor.id())) {
                    continue;
                }
                Candidate target = byId.get(depId);
                switch (dependency.kind()) {
                    case REQUIRED -> {
                        if (target == null) {
                            errors.add(new ModLoadError(descriptor.id(), candidate.file(), "RESOLVE",
                                    "required dependency '" + depId + "' is not installed",
                                    dependency.reason() == null ? "" : dependency.reason(),
                                    "install mod '" + depId + "'"
                                            + ("*".equals(dependency.versionRange()) ? ""
                                            : " with version " + dependency.versionRange())
                                            + " or remove the dependency"));
                            continue;
                        }
                        if (!versionSatisfies(target, dependency, descriptor, candidate, errors)) {
                            continue;
                        }
                        addEdge(after, depId, descriptor.id());
                        addOrderingEdges(after, descriptor.id(), depId, dependency.ordering());
                    }
                    case OPTIONAL -> {
                        if (target == null) {
                            continue; // fine; ordering constraint dropped
                        }
                        addEdge(after, depId, descriptor.id());
                        addOrderingEdges(after, descriptor.id(), depId, dependency.ordering());
                    }
                    case INCOMPATIBLE -> {
                        if (target != null) {
                            errors.add(new ModLoadError(descriptor.id(), candidate.file(), "RESOLVE",
                                    "mod '" + depId + "' is present but declared incompatible",
                                    dependency.reason() == null ? "" : dependency.reason(),
                                    "remove '" + depId + "' or '" + descriptor.id() + "'"));
                        }
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            return new Result(List.of(), errors);
        }

        List<Candidate> order = topologicalOrder(byId, after);
        if (order.size() < byId.size()) {
            List<String> cycle = findCycle(after, byId.keySet(), order);
            errors.add(new ModLoadError("<runtime>", null, "RESOLVE",
                    "dependency cycle detected: " + String.join(" -> ", cycle),
                    "", "break the cycle by removing or reordering one of the dependencies"));
            return new Result(List.of(), errors);
        }
        return new Result(order, List.of());
    }

    private static boolean versionSatisfies(Candidate target, ModDependency dependency,
                                            ModDescriptor requiring, Candidate requiringCandidate,
                                            List<ModLoadError> errors) {
        String range = dependency.versionRange();
        String found = target.descriptor().version();
        boolean ok;
        try {
            ok = Version.parse(found).satisfies(range);
        } catch (RuntimeException e) {
            errors.add(new ModLoadError(requiring.id(), requiringCandidate.file(), "RESOLVE",
                    "cannot evaluate version range '" + range + "' against '" + found + "'",
                    e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""),
                    "fix the version range in the dependency declaration"));
            return false;
        }
        if (!ok) {
            errors.add(new ModLoadError(requiring.id(), requiringCandidate.file(), "RESOLVE",
                    "requires '" + dependency.modId() + "' version '" + range + "' but found " + found,
                    "", "install a compatible version of '" + dependency.modId() + "'"));
        }
        return ok;
    }

    private static void addOrderingEdges(Map<String, Set<String>> after, String modId, String depId,
                                         ModOrder ordering) {
        if (ordering == ModOrder.BEFORE) {
            addEdge(after, modId, depId);
        } else if (ordering == ModOrder.AFTER) {
            addEdge(after, depId, modId);
        }
    }

    private static void addEdge(Map<String, Set<String>> after, String from, String to) {
        if (!from.equals(to)) {
            after.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
        }
    }

    private static List<Candidate> topologicalOrder(Map<String, Candidate> byId,
                                                    Map<String, Set<String>> after) {
        Map<String, Integer> indegree = new HashMap<>();
        byId.keySet().forEach(id -> indegree.put(id, 0));
        after.forEach((from, targets) -> targets.forEach(to -> indegree.merge(to, 1, Integer::sum)));

        PriorityQueue<Candidate> ready = new PriorityQueue<>(Comparator
                .<Candidate>comparingInt(c -> c.descriptor().system() ? 0 : 1)
                .thenComparing(c -> c.descriptor().id()));
        byId.forEach((id, candidate) -> {
            if (indegree.get(id) == 0) {
                ready.add(candidate);
            }
        });

        List<Candidate> order = new ArrayList<>(byId.size());
        while (!ready.isEmpty()) {
            Candidate candidate = ready.poll();
            order.add(candidate);
            for (String to : after.getOrDefault(candidate.descriptor().id(), Set.of())) {
                if (indegree.merge(to, -1, Integer::sum) == 0) {
                    ready.add(byId.get(to));
                }
            }
        }
        return order;
    }

    private static List<String> findCycle(Map<String, Set<String>> after, Set<String> allIds,
                                          List<Candidate> ordered) {
        Set<String> remaining = new LinkedHashSet<>(allIds);
        ordered.forEach(c -> remaining.remove(c.descriptor().id()));
        Map<String, Integer> state = new HashMap<>(); // absent=unvisited, 1=in stack, 2=done
        Deque<String> stack = new ArrayDeque<>();
        for (String start : remaining) {
            List<String> cycle = dfsCycle(start, after, remaining, state, stack);
            if (cycle != null) {
                return cycle;
            }
        }
        return new ArrayList<>(remaining);
    }

    private static List<String> dfsCycle(String node, Map<String, Set<String>> after,
                                         Set<String> remaining, Map<String, Integer> state,
                                         Deque<String> stack) {
        state.put(node, 1);
        stack.addLast(node);
        for (String next : after.getOrDefault(node, Set.of())) {
            if (!remaining.contains(next)) {
                continue;
            }
            Integer nextState = state.get(next);
            if (nextState != null && nextState == 1) {
                List<String> cycle = new ArrayList<>();
                boolean collecting = false;
                for (String element : stack) {
                    if (element.equals(next)) {
                        collecting = true;
                    }
                    if (collecting) {
                        cycle.add(element);
                    }
                }
                cycle.add(next);
                return cycle;
            }
            if (nextState == null) {
                List<String> cycle = dfsCycle(next, after, remaining, state, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        stack.removeLast();
        state.put(node, 2);
        return null;
    }
}
