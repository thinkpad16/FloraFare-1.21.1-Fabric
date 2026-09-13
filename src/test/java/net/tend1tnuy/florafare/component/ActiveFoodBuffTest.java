package net.tend1tnuy.florafare.component;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The saved shape of an active buff. This became load-bearing when buff attribute
 * modifiers moved from persistent to temporary: they are no longer in the player's
 * vanilla attribute NBT, so this record is now the <em>only</em> copy, and anything it
 * drops is a stat the player silently loses on their next login.
 */
class ActiveFoodBuffTest {

    private static final Identifier MOD_ID    = Identifier.of("florafare", "attr_minecraft_bread_attack_damage");
    private static final Identifier ATTRIBUTE = Identifier.of("minecraft", "generic.attack_damage");

    @Test
    @DisplayName("target, item, durations and modifier records all survive a round-trip")
    void roundTripsThroughNbt() {
        ActiveFoodBuff original = new ActiveFoodBuff(
                "minecraft:bread", "minecraft:bread", 4200, 6000);
        original.addModifierRecord(MOD_ID, ATTRIBUTE, 2.5, "add_multiplied_base");

        ActiveFoodBuff restored = ActiveFoodBuff.fromNbt(original.toNbt());

        assertEquals("minecraft:bread", restored.getTarget());
        assertEquals("minecraft:bread", restored.getConsumedItemId());
        assertEquals(4200, restored.getDurationRemaining());
        assertEquals(6000, restored.getInitialDuration());

        ActiveFoodBuff.AppliedModifier modifier = restored.getAppliedModifiers().get(MOD_ID);
        assertNotNull(modifier, "the modifier record is the only copy once modifiers are temporary");
        assertEquals(ATTRIBUTE, modifier.attributeId());
        assertEquals(2.5, modifier.amount(), 1e-9);
        assertEquals("add_multiplied_base", modifier.operation());
    }

    @Test
    @DisplayName("a tag-targeted buff keeps both the tag it came from and the item eaten")
    void keepsTagTargetAndConcreteItemApart() {
        ActiveFoodBuff buff = new ActiveFoodBuff(
                "#c:foods/berry", "minecraft:sweet_berries", 1200, 1200);

        ActiveFoodBuff restored = ActiveFoodBuff.fromNbt(buff.toNbt());

        assertEquals("#c:foods/berry", restored.getTarget(),
                "synergy requirements match on the target");
        assertEquals("minecraft:sweet_berries", restored.getConsumedItemId(),
                "the HUD icon and journal unlock need the concrete item");
    }

    @Test
    @DisplayName("a buff saved with no modifiers loads cleanly")
    void handlesBuffsWithoutModifiers() {
        ActiveFoodBuff plain = new ActiveFoodBuff("minecraft:apple", "minecraft:apple", 600, 1200);
        ActiveFoodBuff restored = ActiveFoodBuff.fromNbt(plain.toNbt());

        assertTrue(restored.getAppliedModifiers().isEmpty());
        assertEquals(600, restored.getDurationRemaining());
    }

    @Test
    @DisplayName("a save from before consumed-item tracking falls back instead of failing")
    void toleratesLegacyEntries() {
        NbtCompound legacy = new NbtCompound();
        legacy.putString("Target", "minecraft:bread");
        legacy.putInt("Duration", 100);
        legacy.putInt("InitialDuration", 200);

        ActiveFoodBuff restored = ActiveFoodBuff.fromNbt(legacy);

        assertEquals("minecraft:bread", restored.getTarget());
        assertEquals("minecraft:apple", restored.getConsumedItemId());
    }

    @Test
    @DisplayName("ticking down to zero marks the buff expired exactly once")
    void expiresWhenTheTimerRunsOut() {
        ActiveFoodBuff buff = new ActiveFoodBuff("minecraft:apple", "minecraft:apple", 3, 3);

        assertFalse(buff.isExpired());
        buff.tick();
        buff.tick();
        assertFalse(buff.isExpired());
        buff.tick();
        assertTrue(buff.isExpired());

        buff.tick();
        assertEquals(0, buff.getDurationRemaining(), "an expired buff must not tick negative");
    }

    @Test
    @DisplayName("re-eating a food resets both the remaining and the full duration")
    void resetDurationRestartsTheBar() {
        ActiveFoodBuff buff = new ActiveFoodBuff("minecraft:apple", "minecraft:apple", 10, 1200);

        buff.resetDuration(3000);

        assertEquals(3000, buff.getDurationRemaining());
        assertEquals(3000, buff.getInitialDuration(),
                "the HUD progress bar reads from the initial duration");
    }
}
