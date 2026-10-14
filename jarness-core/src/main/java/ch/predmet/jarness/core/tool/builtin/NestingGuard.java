package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.tool.ToolContext;

import java.util.Optional;

/**
 * Shared subagent nesting guard: depth is tracked on the agent's extension context under
 * {@code subagent.depth}; spawning is denied once the caller sits at depth 2 or deeper.
 */
final class NestingGuard {

    static final String KEY = "subagent.depth";
    static final int MAX_DEPTH = 2;

    private NestingGuard() {
    }

    /** @return the caller's nesting depth, {@code 0} when untracked */
    static int depth(ToolContext ctx) {
        Optional<Object> value = ctx.agent().ctx().get(KEY);
        if (value.isEmpty()) {
            return 0;
        }
        Object raw = value.get();
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return whether the calling agent may spawn another agent */
    static boolean allowed(ToolContext ctx) {
        return depth(ctx) < MAX_DEPTH;
    }
}
