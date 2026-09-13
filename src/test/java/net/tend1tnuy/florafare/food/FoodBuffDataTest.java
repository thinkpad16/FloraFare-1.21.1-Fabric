package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FoodBuffData} survives the NBT round-trip it makes on every save and every
 * config sync — including from a config written by an older version, which is what a
 * player's existing world hands it on the first load after an update.
 */
class FoodBuffDataTest {

    private static final Identifier SPEED = Identifier.of("minecraft", "generic.movement_speed");
    private static final Identifier REGEN = Identifier.of("minecraft", "regeneration");

    private static FoodBuffData sample() {
        return FoodBuffData.builder("minecraft:golden_carrot")
                .duration(6000)
                .nutrition(6)
                .saturation(1.2f)
                .healthBonus(4.0)
                .priority(2)
                .alwaysEdible(true)
                .addEffect(REGEN, 1200, 1)
                .addAttribute(SPEED, 0.1, "add_multiplied_total")
                .build();
    }

    @Nested
    @DisplayName("NBT round-trip")
    class RoundTrip {

        @Test
        @DisplayName("every field survives being written and read back")
        void preservesAllFields() {
            FoodBuffData original = sample();
            FoodBuffData restored = FoodBuffData.fromNbt(original.toNbt());

            assertEquals(original.target(), restored.target());
            assertEquals(original.duration(), restored.duration());
            assertEquals(original.nutrition(), restored.nutrition());
            assertEquals(original.saturation(), restored.saturation());
            assertEquals(original.healthBonus(), restored.healthBonus());
            assertEquals(original.priority(), restored.priority());
            assertEquals(original.alwaysEdible(), restored.alwaysEdible());
            assertEquals(original.effects(), restored.effects());
            assertEquals(original.attributes(), restored.attributes());
        }

        @Test
        @DisplayName("a config written before the later fields existed reads as defaults, not as a crash")
        void toleratesMissingFields() {
            NbtCompound legacy = new NbtCompound();
            legacy.putString("target", "minecraft:bread");
            legacy.putInt("duration", 1200);

            FoodBuffData restored = FoodBuffData.fromNbt(legacy);

            assertEquals("minecraft:bread", restored.target());
            assertEquals(1200, restored.duration());
            assertFalse(restored.alwaysEdible());
            assertEquals(0, restored.priority());
            assertTrue(restored.effects().isEmpty());
        }
    }
}
