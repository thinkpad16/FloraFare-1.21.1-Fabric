package net.tend1tnuy.florafare.component;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether an entry is a synergy is recorded on the entry, not inferred from its target.
 *
 * <p>The inference — "ask FoodSynergyManager whether anything is registered under this
 * target" — was wrong in both directions. A food buff whose datapack target collided with
 * a synergy id was removed as though it were a synergy, firing {@code SYNERGY_ENDED} at
 * every listener and running the status-effect cleanup against another definition's data.
 * A synergy whose definition a {@code /reload} had removed stopped being recognised as
 * one, so its effects were never taken back. Nothing separates the two id spaces, so the
 * answer has to be carried rather than derived.
 */
class SynergyFlagTest {

    @Test
    @DisplayName("a food buff is not a synergy")
    void foodBuffDefaultsToFalse() {
        assertFalse(new ActiveFoodBuff("minecraft:bread", "minecraft:bread", 600, 600)
                .isSynergy());
    }

    @Test
    @DisplayName("the synergy factory says so")
    void synergyFactory() {
        assertTrue(ActiveFoodBuff.synergy("florafare:golden_feast", "minecraft:golden_apple", 600, 600)
                .isSynergy());
    }

    @Test
    @DisplayName("the flag survives a save/load round trip")
    void survivesNbt() {
        ActiveFoodBuff synergy =
                ActiveFoodBuff.synergy("florafare:ocean_bounty", "minecraft:cod", 400, 600);
        assertTrue(ActiveFoodBuff.fromNbt(synergy.toNbt()).isSynergy());

        ActiveFoodBuff food = new ActiveFoodBuff("minecraft:bread", "minecraft:bread", 400, 600);
        assertFalse(ActiveFoodBuff.fromNbt(food.toNbt()).isSynergy());
    }

    @Test
    @DisplayName("a collision between a buff target and a synergy id no longer confuses the two")
    void targetCollisionIsHarmless() {
        // Both name "florafare:hearty_lunch" — one because a datapack used it as a food
        // buff target, one because it is a synergy id.
        ActiveFoodBuff buff = new ActiveFoodBuff("florafare:hearty_lunch", "minecraft:bread", 600, 600);
        ActiveFoodBuff synergy = ActiveFoodBuff.synergy("florafare:hearty_lunch", "minecraft:bread", 600, 600);

        assertEquals(buff.getTarget(), synergy.getTarget());
        assertNotEquals(buff.isSynergy(), synergy.isSynergy(),
                "identical targets, and they must still be told apart");
    }

    @Test
    @DisplayName("an entry saved before the flag existed is read back as a synergy from the synergy list")
    void legacySynergyEntry() {
        // Written by a pre-fix version: a synergy, but with no "Synergy" key at all.
        NbtCompound legacy = new NbtCompound();
        legacy.putString("Target", "florafare:golden_feast");
        legacy.putString("ConsumedItem", "minecraft:golden_apple");
        legacy.putInt("Duration", 400);
        legacy.putInt("InitialDuration", 600);

        assertFalse(ActiveFoodBuff.fromNbt(legacy).isSynergy(),
                "read on its own there is nothing to go on");
        assertTrue(ActiveFoodBuff.fromNbt(legacy, true).isSynergy(),
                "but the synergy list knows its own entries are synergies, which is how "
                        + "an existing save keeps working across the upgrade");
    }
}
