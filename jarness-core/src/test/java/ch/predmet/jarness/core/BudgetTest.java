package ch.predmet.jarness.core;

import ch.predmet.jarness.api.kernel.Budget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetTest {

    @Test
    void unlimitedNeverExpiresAndAllowsSpawn() {
        Budget budget = Budget.unlimited();
        assertTrue(budget.unlimitedTokens());
        assertTrue(budget.hasTokens());
        assertTrue(budget.allowsSpawn());
        assertFalse(budget.expired());
        assertEquals(budget, budget.consumeTokens(1_000));
    }

    @Test
    void finiteBudgetCountsDownAndStopsAtZero() {
        Budget budget = Budget.tokens(100);
        Budget after = budget.consumeTokens(40);
        assertEquals(60, after.tokensLeft());
        assertEquals(0, after.consumeTokens(1_000).tokensLeft());
        assertFalse(after.consumeTokens(60).hasTokens());
    }

    @Test
    void deadlinesAreAbsolute() {
        Budget past = Budget.of(-1, Instant.now().minus(Duration.ofSeconds(1)), -1, -1);
        Budget future = Budget.of(-1, Instant.now().plus(Duration.ofMinutes(1)), -1, -1);
        assertTrue(past.expired());
        assertFalse(future.expired());
    }

    @Test
    void childBudgetMustFitInsideTheParent() {
        Budget parent = Budget.of(100, Instant.now().plus(Duration.ofHours(1)), -1, 4);
        assertTrue(Budget.of(50, parent.deadline(), -1, 3).fitsWithin(parent));
        assertFalse(Budget.tokens(200).fitsWithin(parent));
        assertFalse(Budget.of(50, parent.deadline(), -1, 5).fitsWithin(parent));
        assertFalse(Budget.of(50, Instant.now().plus(Duration.ofHours(2)), -1, 2).fitsWithin(parent));
        assertFalse(Budget.unlimited().fitsWithin(parent), "an unbounded child does not fit a bounded parent");
    }

    @Test
    void childDefaultSpendsOneSpawnLevel() {
        Budget parent = Budget.of(100, null, 10, 3);
        Budget child = parent.childDefault();
        assertEquals(100, child.tokensLeft());
        assertEquals(2, child.maxSpawnDepth());
        assertTrue(child.fitsWithin(parent));
        assertEquals(0, child.withMaxSpawnDepth(0).maxSpawnDepth());
        assertFalse(child.withMaxSpawnDepth(0).allowsSpawn());
    }
}
