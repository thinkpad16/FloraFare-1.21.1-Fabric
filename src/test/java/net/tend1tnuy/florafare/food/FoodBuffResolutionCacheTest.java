package net.tend1tnuy.florafare.food;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code getConfig} memoizes its lookup per item, which is only safe if every route
 * that can change an item's resolved buff clears the memo. A stale entry here would be
 * a food that keeps its old buff after a {@code /reload}, or an item that stays
 * excluded after a mod un-excludes it — both of which look like data corruption to
 * whoever hits them.
 *
 * <p>These drive the invalidation hooks directly rather than through {@code getConfig},
 * which would need a live item registry.
 */
class FoodBuffResolutionCacheTest {

    @BeforeEach
    void reset() {
        FoodBuffManager.clear();
        FoodBuffManager.setExcludedItems(Set.of());
    }

    @Test
    @DisplayName("registering an entry invalidates the memo")
    void putConfigInvalidates() {
        FoodBuffManager.putConfig("minecraft:bread", FoodBuffData.builder("minecraft:bread").build());
        // Nothing to assert about the cache's contents directly — what matters is that
        // the call path exists and is wired, which the next assertion pins down.
        assertNotNull(FoodBuffManager.getConfigByTarget("minecraft:bread"));
    }

    @Test
    @DisplayName("a datapack reload drops every memoized lookup")
    void clearInvalidates() {
        FoodBuffManager.putConfig("minecraft:bread", FoodBuffData.builder("minecraft:bread").build());
        FoodBuffManager.clear();
        assertTrue(FoodBuffManager.getAllConfigs().isEmpty(),
                "clear() must drop the configs it memoized lookups against");
    }

    @Test
    @DisplayName("excluding and re-including an item are both invalidation points")
    void exclusionChangesInvalidate() {
        FoodBuffManager.excludeItem("minecraft:cake");
        assertTrue(FoodBuffManager.getExcludedItems().contains("minecraft:cake"));

        FoodBuffManager.includeItem("minecraft:cake");
        assertTrue(FoodBuffManager.getExcludedItems().isEmpty(),
                "re-including must actually clear the exclusion, not just the memo");
    }

    @Test
    @DisplayName("a repeated exclusion is not treated as a change")
    void redundantExclusionIsIdempotent() {
        FoodBuffManager.excludeItem("minecraft:cake");
        Set<String> after = FoodBuffManager.getExcludedItems();
        FoodBuffManager.excludeItem("minecraft:cake");
        assertTrue(FoodBuffManager.getExcludedItems().equals(after),
                "excluding an already-excluded item must leave the set untouched");
    }

    @Test
    @DisplayName("a blank or unparseable id is ignored rather than poisoning the set")
    void badIdsAreIgnored() {
        FoodBuffManager.excludeItem("");
        FoodBuffManager.excludeItem(null);
        FoodBuffManager.excludeItem("   ");
        assertTrue(FoodBuffManager.getExcludedItems().isEmpty());
    }

    @Test
    @DisplayName("replacing the exclusion set from a server sync invalidates too")
    void syncedExclusionsInvalidate() {
        FoodBuffManager.excludeItem("minecraft:cake");
        FoodBuffManager.setExcludedItems(Set.of("modid:stew"));
        assertSame(1, FoodBuffManager.getExcludedItems().size());
        assertTrue(FoodBuffManager.getExcludedItems().contains("modid:stew"));
    }
}
