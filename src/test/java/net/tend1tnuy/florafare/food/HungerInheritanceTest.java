package net.tend1tnuy.florafare.food;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An entry that says nothing about hunger keeps the eaten item's own, and an entry with
 * no duration is hunger-only.
 *
 * <p>Both fields used to default to zero, and Florafare's numbers replace the item's on
 * every managed bite — so an entry written only to attach a buff turned its food into one
 * that restored nothing. Explicit values, zero and negative included, must still win: a
 * negative nutrition that drains hunger is a feature packs are built around.
 */
class HungerInheritanceTest {

    private static FoodBuffData withHunger(int nutrition, float saturation) {
        return new FoodBuffData("minecraft:bread", 6000, nutrition, saturation, 0,
                null, null, 0, false);
    }

    @Nested
    @DisplayName("nutrition")
    class Nutrition {

        @Test
        @DisplayName("an absent value resolves to the item's own")
        void inherits() {
            assertEquals(5, FoodBuffData.resolveNutrition(FoodBuffData.INHERIT_NUTRITION, 5));
        }

        @Test
        @DisplayName("an explicit value wins, zero included")
        void explicitWins() {
            assertEquals(8, FoodBuffData.resolveNutrition(8, 5));
            assertEquals(0, FoodBuffData.resolveNutrition(0, 5));
        }

        @Test
        @DisplayName("a negative value is honored, so a food can drain hunger")
        void negativeHonored() {
            assertEquals(-4, FoodBuffData.resolveNutrition(-4, 5));
        }

        @Test
        @DisplayName("no food component means nothing to inherit")
        void nullFood() {
            assertEquals(0, withHunger(FoodBuffData.INHERIT_NUTRITION,
                    FoodBuffData.INHERIT_SATURATION).effectiveNutrition((net.minecraft.component.type.FoodComponent) null));
            assertEquals(3, withHunger(3, 0.5f)
                    .effectiveNutrition((net.minecraft.component.type.FoodComponent) null));
        }
    }

    @Nested
    @DisplayName("saturation")
    class Saturation {

        @Test
        @DisplayName("an absent modifier is the one the item implies")
        void inheritsModifier() {
            // Bread: 5 nutrition, 6.0 saturation points, so a modifier of 0.6.
            assertEquals(0.6f, FoodBuffData.resolveSaturationModifier(
                    FoodBuffData.INHERIT_SATURATION, 5, 6.0f), 1e-6f);
        }

        @Test
        @DisplayName("an explicit modifier wins, zero included")
        void explicitWins() {
            assertEquals(1.2f, FoodBuffData.resolveSaturationModifier(1.2f, 5, 6.0f));
            assertEquals(0.0f, FoodBuffData.resolveSaturationModifier(0.0f, 5, 6.0f));
        }

        @Test
        @DisplayName("an item with no nutrition implies no modifier, not a division by zero")
        void zeroNutrition() {
            assertEquals(0.0f, FoodBuffData.resolveSaturationModifier(
                    FoodBuffData.INHERIT_SATURATION, 0, 3.0f));
        }

        @Test
        @DisplayName("inheriting the modifier alone keeps the item's ratio at the new nutrition")
        void keepsRatio() {
            float modifier = FoodBuffData.resolveSaturationModifier(
                    FoodBuffData.INHERIT_SATURATION, 5, 6.0f);
            assertEquals(12.0f, FoodBuffData.saturationPoints(10, modifier), 1e-5f);
        }
    }

    @Nested
    @DisplayName("the record")
    class Record {

        @Test
        @DisplayName("knows which halves it sets")
        void flags() {
            FoodBuffData inherits = withHunger(FoodBuffData.INHERIT_NUTRITION,
                    FoodBuffData.INHERIT_SATURATION);
            assertFalse(inherits.hasNutrition());
            assertFalse(inherits.hasSaturation());
            assertTrue(inherits.inheritsHunger());

            FoodBuffData mixed = withHunger(4, FoodBuffData.INHERIT_SATURATION);
            assertTrue(mixed.hasNutrition());
            assertFalse(mixed.hasSaturation());
            assertFalse(mixed.inheritsHunger());

            FoodBuffData zero = withHunger(0, 0f);
            assertTrue(zero.hasNutrition(), "an explicit 0 is a value, not an absence");
            assertTrue(zero.hasSaturation());
        }

        @Test
        @DisplayName("the builder defaults to inheriting, like the datapack loader")
        void builderInherits() {
            FoodBuffData built = FoodBuffData.builder("minecraft:bread").build();
            assertTrue(built.inheritsHunger());
        }

        @Test
        @DisplayName("the sentinels survive the NBT round-trip every save and sync makes")
        void nbtRoundTrip() {
            FoodBuffData original = withHunger(FoodBuffData.INHERIT_NUTRITION,
                    FoodBuffData.INHERIT_SATURATION);
            FoodBuffData restored = FoodBuffData.fromNbt(original.toNbt());
            assertFalse(restored.hasNutrition());
            assertFalse(restored.hasSaturation());
            assertEquals(original, restored);

            FoodBuffData negative = withHunger(-6, 0.25f);
            FoodBuffData negativeRestored = FoodBuffData.fromNbt(negative.toNbt());
            assertEquals(-6, negativeRestored.nutrition());
            assertEquals(0.25f, negativeRestored.saturation());
        }
    }

    @Nested
    @DisplayName("hunger-only entries")
    class HungerOnly {

        private FoodBuffData withDuration(int duration) {
            return FoodBuffData.builder("minecraft:bread").duration(duration).build();
        }

        @Test
        @DisplayName("a duration of zero grants no buff")
        void zero() {
            assertTrue(withDuration(0).isHungerOnly());
        }

        @Test
        @DisplayName("a negative duration grants no buff either")
        void negative() {
            assertTrue(withDuration(-20).isHungerOnly());
        }

        @Test
        @DisplayName("any positive duration is an ordinary buff")
        void positive() {
            assertFalse(withDuration(1).isHungerOnly());
            assertFalse(withDuration(6000).isHungerOnly());
        }
    }
}
