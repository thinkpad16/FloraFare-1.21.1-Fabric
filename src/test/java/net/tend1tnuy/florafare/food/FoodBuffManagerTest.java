package net.tend1tnuy.florafare.food;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Config resolution: how a raw datapack target string is normalized, which of two
 * entries for the same target survives, and which tag wins when an item is in several
 * configured tags at once. These rules are documented in the README and datapacks are
 * written against them, so they are behaviour, not implementation detail.
 */
class FoodBuffManagerTest {

    private static FoodBuffData tagEntry(String target, int priority) {
        return FoodBuffData.builder(target).priority(priority).build();
    }

    @Nested
    @DisplayName("target normalization")
    class Normalization {

        @Test
        @DisplayName("a bare path gets the minecraft namespace")
        void addsDefaultNamespace() {
            assertEquals("minecraft:bread", FoodBuffManager.normalizeTarget("bread"));
        }

        @Test
        @DisplayName("surrounding whitespace is forgiven")
        void trimsWhitespace() {
            assertEquals("minecraft:bread", FoodBuffManager.normalizeTarget("  minecraft:bread  "));
        }

        @Test
        @DisplayName("tags keep their hash and get the same namespace treatment")
        void normalizesTags() {
            assertEquals("#c:foods/vegetable", FoodBuffManager.normalizeTarget("#c:foods/vegetable"));
            assertEquals("#minecraft:fishes", FoodBuffManager.normalizeTarget("#fishes"));
        }

        @Test
        @DisplayName("the special prefixes are passed through untouched")
        void leavesSpecialPrefixesAlone() {
            assertEquals("namespace:farmersdelight",
                    FoodBuffManager.normalizeTarget("namespace:farmersdelight"));
            assertEquals("template:default", FoodBuffManager.normalizeTarget("template:default"));
            assertEquals("potion:minecraft:strength",
                    FoodBuffManager.normalizeTarget("potion:minecraft:strength"));
            assertEquals("stack:abc-123", FoodBuffManager.normalizeTarget("stack:abc-123"));
        }

        @Test
        @DisplayName("an unparseable target is handed back as-is rather than swallowed")
        void keepsInvalidInputVisible() {
            assertEquals("NOT AN ID", FoodBuffManager.normalizeTarget("NOT AN ID"));
            assertNull(FoodBuffManager.normalizeTarget(null));
        }
    }

    @Nested
    @DisplayName("tag ranking")
    class TagRanking {

        @Test
        @DisplayName("any configured tag beats no match at all")
        void anyCandidateBeatsNothing() {
            assertTrue(FoodBuffManager.isBetterTagMatch(tagEntry("#c:foods", 0), null));
        }

        @Test
        @DisplayName("higher priority wins outright")
        void priorityDominates() {
            FoodBuffData high = tagEntry("#c:foods", 5);
            FoodBuffData low  = tagEntry("#c:foods/vegetable/root", 0);

            assertTrue(FoodBuffManager.isBetterTagMatch(high, low),
                    "an explicit priority must override tag depth");
            assertFalse(FoodBuffManager.isBetterTagMatch(low, high));
        }

        @Test
        @DisplayName("at equal priority the more specific tag wins, so broad tags act as fallbacks")
        void deeperTagWinsOnATie() {
            FoodBuffData broad    = tagEntry("#c:foods", 0);
            FoodBuffData specific = tagEntry("#c:foods/vegetable", 0);

            assertTrue(FoodBuffManager.isBetterTagMatch(specific, broad));
            assertFalse(FoodBuffManager.isBetterTagMatch(broad, specific));
        }

        @Test
        @DisplayName("same depth falls back to the longer, then the alphabetically first path")
        void remainingTiesAreDeterministic() {
            FoodBuffData shortPath = tagEntry("#c:aa", 0);
            FoodBuffData longPath  = tagEntry("#c:aaaa", 0);
            assertTrue(FoodBuffManager.isBetterTagMatch(longPath, shortPath));

            FoodBuffData alpha = tagEntry("#c:aaa", 0);
            FoodBuffData beta  = tagEntry("#c:bbb", 0);
            assertTrue(FoodBuffManager.isBetterTagMatch(alpha, beta));
            assertFalse(FoodBuffManager.isBetterTagMatch(beta, alpha));
        }

        @Test
        @DisplayName("ranking is a strict order — two entries can't both beat each other")
        void rankingIsAntisymmetric() {
            String[] targets = {"#c:foods", "#c:foods/vegetable", "#c:foods/vegetable/root", "#c:bread"};
            for (String a : targets) {
                for (String b : targets) {
                    if (a.equals(b)) continue;
                    FoodBuffData left  = tagEntry(a, 0);
                    FoodBuffData right = tagEntry(b, 0);
                    assertNotEquals(
                            FoodBuffManager.isBetterTagMatch(left, right),
                            FoodBuffManager.isBetterTagMatch(right, left),
                            "ambiguous ranking between " + a + " and " + b);
                }
            }
        }
    }

    @Nested
    @DisplayName("conflicting definitions")
    class Conflicts {

        @BeforeEach
        void clearConfigs() {
            FoodBuffManager.clear();
        }

        @Test
        @DisplayName("the higher-priority definition of a target wins")
        void higherPriorityWins() {
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(0).duration(100).build());
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(5).duration(999).build());

            assertEquals(999, FoodBuffManager.getConfigByTarget("minecraft:bread").duration());
        }

        @Test
        @DisplayName("a lower-priority definition can't overwrite a higher one, whatever the load order")
        void lowerPriorityLoadedLaterIsIgnored() {
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(5).duration(999).build());
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(0).duration(100).build());

            assertEquals(999, FoodBuffManager.getConfigByTarget("minecraft:bread").duration());
        }

        @Test
        @DisplayName("two datapacks claiming a target at the same priority are flagged for /florafare validate")
        void samePriorityIsReportedAsAmbiguous() {
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(1).build());
            assertFalse(FoodBuffManager.getAmbiguousTargets().contains("minecraft:bread"));

            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(1).build());
            assertTrue(FoodBuffManager.getAmbiguousTargets().contains("minecraft:bread"));
        }

        @Test
        @DisplayName("a decisive third definition clears the ambiguity flag")
        void ambiguityClearsOnceSomethingWins() {
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(1).build());
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(1).build());
            FoodBuffManager.putConfig("minecraft:bread",
                    FoodBuffData.builder("minecraft:bread").priority(9).build());

            assertFalse(FoodBuffManager.getAmbiguousTargets().contains("minecraft:bread"));
        }
    }
}
