package ch.predmet.jarness.api.capability;

import java.time.Instant;

/**
 * The limits attached to one capability grant. Constraints may only get narrower as capabilities
 * are inherited: a child can never widen a token budget, extend a deadline, or gain spawn depth.
 *
 * <p>A value of {@code -1} means "unbounded", and a {@code null} deadline means "no deadline".
 * That makes the unbounded value the widest possible constraint, so the attenuation rule reads:
 * a parent constraint covers a requested constraint only when the requested one is at least as
 * narrow.</p>
 *
 * @param scope         resource scope interpreted by the execution side, e.g. {@code /data/team};
 *                      {@code *} means "any resource of this kind"
 * @param maxTokens     token budget for this capability, or {@code -1} for unbounded
 * @param deadline      absolute deadline, or {@code null} for none
 * @param maxSpawnDepth maximum spawn depth permitted under this capability, or {@code -1} for unbounded
 */
public record Constraints(String scope, long maxTokens, Instant deadline, int maxSpawnDepth) {

    public Constraints {
        scope = scope == null || scope.isBlank() ? "*" : scope.trim();
    }

    /** @return the widest constraints: any resource, no limits. */
    public static Constraints unbounded() {
        return new Constraints("*", -1L, null, -1);
    }

    /** @return constraints on one resource scope, otherwise unbounded. */
    public static Constraints scoped(String scope) {
        return new Constraints(scope, -1L, null, -1);
    }

    /** @return whether every limit is unbounded. */
    public boolean isUnbounded() {
        return maxTokens < 0 && deadline == null && maxSpawnDepth < 0;
    }

    /**
     * @param requested the constraints a caller asks for
     * @return whether these constraints are at least as permissive as {@code requested}, so any
     *         resource allowed by the request is also allowed here. An unbounded constraint covers
     *         every request; a bounded one only covers requests that stay inside its bounds.
     */
    public boolean covers(Constraints requested) {
        if (requested == null) {
            return false;
        }
        if (maxTokens >= 0 && (requested.maxTokens < 0 || requested.maxTokens > maxTokens)) {
            return false;
        }
        if (deadline != null && (requested.deadline == null || requested.deadline.isAfter(deadline))) {
            return false;
        }
        return maxSpawnDepth < 0 || (requested.maxSpawnDepth >= 0 && requested.maxSpawnDepth <= maxSpawnDepth);
    }

    /**
     * Intersect two constraint sets, keeping the narrower value of each limit.
     *
     * @param other the other constraints
     * @return the narrower intersection
     */
    public Constraints intersect(Constraints other) {
        if (other == null) {
            return this;
        }
        long tokens = narrowerTokens(maxTokens, other.maxTokens);
        Instant when = narrowerDeadline(deadline, other.deadline);
        int depth = narrowerDepth(maxSpawnDepth, other.maxSpawnDepth);
        String scopeValue = scope.equals(other.scope) ? scope : narrowerScope(scope, other.scope);
        return new Constraints(scopeValue, tokens, when, depth);
    }

    private static String narrowerScope(String a, String b) {
        if (a.equals("*")) {
            return b;
        }
        if (b.equals("*")) {
            return a;
        }
        return a.length() >= b.length() ? a : b;
    }

    private static long narrowerTokens(long a, long b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static Instant narrowerDeadline(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static int narrowerDepth(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }
}
