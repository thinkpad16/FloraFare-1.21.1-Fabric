package net.tend1tnuy.florafare.food;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A config is a shared object, not a private one.
 *
 * <p>{@code FoodBuffManager.getConfig} memoizes one instance per item and hands that same
 * instance to the tooltip, the HUD, the journal, EMI and the eat path; a datapack entry
 * for a {@code #tag} is shared by every item the tag covers. Both records used to expose
 * the very {@code ArrayList} they were built from, so a single caller adding an effect
 * would have changed the buff for every one of those readers, for the rest of the
 * session, with no way to trace it back.
 */
class FoodDataImmutabilityTest {

    private static final Identifier SPEED = Identifier.of("minecraft", "speed");
    private static final Identifier MOVEMENT = Identifier.of("minecraft", "generic.movement_speed");

    @Test
    @DisplayName("a buff's effect list cannot be added to by whoever received it")
    void effectsAreUnmodifiable() {
        FoodBuffData data = FoodBuffData.builder("minecraft:bread")
                .addEffect(SPEED, 200, 0)
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> data.effects().add(new FoodBuffData.EffectData(SPEED, 1, 1)));
        assertEquals(1, data.effects().size());
    }

    @Test
    @DisplayName("a buff's attribute list cannot be added to either")
    void attributesAreUnmodifiable() {
        FoodBuffData data = FoodBuffData.builder("minecraft:bread")
                .addAttribute(MOVEMENT, 0.02, "add_multiplied_base")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> data.attributes().add(
                        new FoodBuffData.AttributeData(MOVEMENT, 99.0, "add_value")));
    }

    @Test
    @DisplayName("mutating the list a buff was constructed from does not reach the buff")
    void constructorCopiesRatherThanAliases() {
        List<FoodBuffData.EffectData> source = new ArrayList<>();
        source.add(new FoodBuffData.EffectData(SPEED, 200, 0));

        FoodBuffData data = new FoodBuffData(
                "minecraft:bread", 600, 5, 0.6f, 0.0, source, List.of(), 0, false);

        source.add(new FoodBuffData.EffectData(SPEED, 999, 9));

        assertEquals(1, data.effects().size(),
                "the record kept a reference to the caller's list instead of copying it");
    }

    @Test
    @DisplayName("null lists are accepted and become empty ones")
    void nullListsBecomeEmpty() {
        FoodBuffData data = new FoodBuffData(
                "minecraft:bread", 600, 5, 0.6f, 0.0, null, null, 0, false);

        assertTrue(data.effects().isEmpty());
        assertTrue(data.attributes().isEmpty());
    }

    @Test
    @DisplayName("a synergy protects its requirements the same way")
    void synergyRequirementsAreUnmodifiable() {
        List<String> requirements = new ArrayList<>(List.of("minecraft:apple"));
        FoodSynergyData synergy = new FoodSynergyData(
                "florafare:test", requirements, 2400, 0.0, List.of(), List.of());

        requirements.add("minecraft:carrot");

        assertEquals(1, synergy.requirements().size());
        assertThrows(UnsupportedOperationException.class,
                () -> synergy.requirements().add("minecraft:bread"));
    }

    @Test
    @DisplayName("an NBT round-trip still produces a usable, and still unmodifiable, config")
    void roundTripStaysImmutable() {
        FoodBuffData original = FoodBuffData.builder("minecraft:bread")
                .addEffect(SPEED, 200, 1)
                .addAttribute(MOVEMENT, 0.02, "add_multiplied_base")
                .build();

        FoodBuffData restored = FoodBuffData.fromNbt(original.toNbt());

        assertEquals(original.effects(), restored.effects());
        assertEquals(original.attributes(), restored.attributes());
        assertThrows(UnsupportedOperationException.class,
                () -> restored.effects().add(new FoodBuffData.EffectData(SPEED, 1, 1)));
    }
}
