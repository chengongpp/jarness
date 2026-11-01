package ch.predmet.jarness.api.kernel;

import java.time.Instant;

/**
 * One agent's quota: tokens, wall-clock deadline, tool slots, and how much deeper it may spawn.
 *
 * <p>Budgets are immutable values; the agent replaces its budget with {@link #consumeTokens} and
 * {@link #consumeToolSlot} results. A negative value means "unlimited", which is also the widest
 * possible budget, so a child is admitted only when its budget fits inside its parent's remaining
 * one ({@link #fitsWithin}).</p>
 *
 * <p>The supervisor validates that a child's budget does not exceed its parent's remaining budget,
 * but it does not yet split and merge quota across the tree: each node keeps its own counters.
 * That approximation is recorded as an open item in {@code docs/design-gap-analysis.md}.</p>
 *
 * @param tokensLeft   tokens remaining, or {@code -1} for unlimited
 * @param deadline     absolute deadline, or {@code null} for none
 * @param toolSlots    remaining tool calls, or {@code -1} for unlimited
 * @param maxSpawnDepth how many more levels this agent may spawn, or {@code -1} for unlimited
 */
public record Budget(long tokensLeft, Instant deadline, int toolSlots, int maxSpawnDepth) {

    /** @return an unlimited budget. */
    public static Budget unlimited() {
        return new Budget(-1L, null, -1, -1);
    }

    /** @return a budget with a finite token allowance and nothing else bounded. */
    public static Budget tokens(long tokens) {
        return new Budget(tokens, null, -1, -1);
    }

    /** @return a budget bounded on every axis. */
    public static Budget of(long tokens, Instant deadline, int toolSlots, int maxSpawnDepth) {
        return new Budget(tokens, deadline, toolSlots, maxSpawnDepth);
    }

    /** @return whether tokens are unlimited. */
    public boolean unlimitedTokens() {
        return tokensLeft < 0;
    }

    /** @return whether this agent may still spend tokens. */
    public boolean hasTokens() {
        return tokensLeft != 0;
    }

    /** @return whether the deadline has passed. */
    public boolean expired() {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /** @return whether this agent may spawn at all. */
    public boolean allowsSpawn() {
        return maxSpawnDepth != 0;
    }

    /**
     * Spend tokens.
     *
     * @param tokens number of tokens, negative values are ignored
     * @return the remaining budget
     */
    public Budget consumeTokens(long tokens) {
        if (tokens <= 0 || tokensLeft < 0) {
            return this;
        }
        return new Budget(Math.max(0L, tokensLeft - tokens), deadline, toolSlots, maxSpawnDepth);
    }

    /**
     * Spend one tool slot.
     *
     * @return the remaining budget
     */
    public Budget consumeToolSlot() {
        if (toolSlots < 0) {
            return this;
        }
        return new Budget(tokensLeft, deadline, Math.max(0, toolSlots - 1), maxSpawnDepth);
    }

    /**
     * @param parent the parent's remaining budget
     * @return whether this budget fits inside the parent's remaining budget
     */
    public boolean fitsWithin(Budget parent) {
        if (parent == null) {
            return true;
        }
        if (!parent.unlimitedTokens() && (tokensLeft < 0 || tokensLeft > parent.tokensLeft)) {
            return false;
        }
        if (parent.deadline != null && (deadline == null || deadline.isAfter(parent.deadline))) {
            return false;
        }
        return parent.maxSpawnDepth < 0
                || (maxSpawnDepth >= 0 && maxSpawnDepth <= parent.maxSpawnDepth);
    }

    /**
     * Derive the default budget of a child: same remaining allowance, one less spawn level.
     *
     * @return the child's default budget
     */
    public Budget childDefault() {
        int depth = maxSpawnDepth < 0 ? -1 : Math.max(0, maxSpawnDepth - 1);
        return new Budget(tokensLeft, deadline, -1, depth);
    }

    /** @return a copy with a different token allowance. */
    public Budget withTokens(long tokens) {
        return new Budget(tokens, deadline, toolSlots, maxSpawnDepth);
    }

    /** @return a copy with a different deadline. */
    public Budget withDeadline(Instant when) {
        return new Budget(tokensLeft, when, toolSlots, maxSpawnDepth);
    }

    /** @return a copy with a different spawn depth. */
    public Budget withMaxSpawnDepth(int depth) {
        return new Budget(tokensLeft, deadline, toolSlots, depth);
    }

    /** @return a compact human-readable form used by logs and diagnostics. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(unlimitedTokens() ? "tokens=unbounded" : "tokens=" + tokensLeft);
        sb.append(" deadline=").append(deadline == null ? "none" : deadline.toString());
        sb.append(" slots=").append(toolSlots < 0 ? "unbounded" : toolSlots);
        sb.append(" depth=").append(maxSpawnDepth < 0 ? "unbounded" : maxSpawnDepth);
        return sb.toString();
    }

    /**
     * Intersect with another budget, keeping the narrower value on every axis.
     *
     * @param other the other budget
     * @return the narrower budget
     */
    public Budget narrower(Budget other) {
        if (other == null) {
            return this;
        }
        long tokens = unlimitedTokens() ? other.tokensLeft
                : other.unlimitedTokens() ? tokensLeft : Math.min(tokensLeft, other.tokensLeft);
        Instant when = deadline == null ? other.deadline
                : other.deadline == null ? deadline
                : (deadline.isBefore(other.deadline) ? deadline : other.deadline);
        int slots = toolSlots < 0 ? other.toolSlots
                : other.toolSlots < 0 ? toolSlots : Math.min(toolSlots, other.toolSlots);
        int depth = maxSpawnDepth < 0 ? other.maxSpawnDepth
                : other.maxSpawnDepth < 0 ? maxSpawnDepth : Math.min(maxSpawnDepth, other.maxSpawnDepth);
        return new Budget(tokens, when, slots, depth);
    }
}
