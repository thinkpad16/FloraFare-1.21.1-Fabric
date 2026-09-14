package net.tend1tnuy.florafare.component;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two properties a busy server depends on: a buff can never become permanent, and
 * journal progress is not left sitting in memory until the next world autosave.
 */
class BuffDurabilityTest {

    @Nested
    @DisplayName("duration ceiling")
    class DurationCeiling {

        @Test
        @DisplayName("a buff created with an absurd duration is capped, not left running for years")
        void constructorCaps() {
            ActiveFoodBuff buff = new ActiveFoodBuff(
                    "minecraft:apple", "minecraft:apple", Integer.MAX_VALUE, Integer.MAX_VALUE);

            assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, buff.getDurationRemaining());
            assertFalse(buff.isExpired());
        }

        @Test
        @DisplayName("a stuck buff already in a save is capped when it loads, so a restart clears it")
        void loadingCaps() {
            // What a player's .dat would hold after any of the ways a duration can run
            // away — an overflowed multiplier, a typo in a datapack, a command argument.
            NbtCompound nbt = new NbtCompound();
            nbt.putString("Target", "minecraft:apple");
            nbt.putString("ConsumedItem", "minecraft:apple");
            nbt.putInt("Duration", Integer.MAX_VALUE);
            nbt.putInt("InitialDuration", Integer.MAX_VALUE);

            ActiveFoodBuff loaded = ActiveFoodBuff.fromNbt(nbt);

            assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, loaded.getDurationRemaining(),
                    "the cap has to apply on load, or a restart preserves the stuck buff "
                            + "instead of healing it");
        }

        @Test
        @DisplayName("a negative duration becomes expired rather than wrapping")
        void negativeBecomesExpired() {
            ActiveFoodBuff buff = new ActiveFoodBuff("minecraft:apple", "minecraft:apple", -5, -5);

            assertEquals(0, buff.getDurationRemaining());
            assertTrue(buff.isExpired());
        }

        @Test
        @DisplayName("refreshing an existing buff is capped too")
        void resetCaps() {
            ActiveFoodBuff buff = new ActiveFoodBuff("minecraft:apple", "minecraft:apple", 600, 600);
            buff.resetDuration(Integer.MAX_VALUE);

            assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, buff.getDurationRemaining());
            assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, buff.getInitialDuration());
        }

        @Test
        @DisplayName("an initial duration below the remaining one is raised, not left to skew the HUD bar")
        void initialNeverBelowRemaining() {
            // The overlay divides remaining by initial; a smaller initial pins the bar
            // full for the buff's whole life.
            ActiveFoodBuff buff = new ActiveFoodBuff("minecraft:apple", "minecraft:apple", 600, 10);

            assertTrue(buff.getInitialDuration() >= buff.getDurationRemaining());
        }

        @Test
        @DisplayName("ordinary durations pass through untouched")
        void normalDurationsUnchanged() {
            ActiveFoodBuff buff = new ActiveFoodBuff("minecraft:bread", "minecraft:bread", 6000, 6000);

            assertEquals(6000, buff.getDurationRemaining());
            assertEquals(6000, buff.getInitialDuration());
        }
    }

    @Nested
    @DisplayName("progress persistence")
    class ProgressPersistence {

        /** A component with no player attached is enough to drive the persist throttle. */
        private PlayerFoodComponent component() {
            return new PlayerFoodComponent(null);
        }

        @Test
        @DisplayName("nothing discovered means nothing to write")
        void idlePlayerIsNeverSaved() {
            assertFalse(component().isPersistDue(0));
            assertFalse(component().isPersistDue(100_000));
        }

        @Test
        @DisplayName("the first discovery of a session is written immediately")
        void firstDiscoverySavesAtOnce() {
            PlayerFoodComponent comp = component();
            comp.getDiscoveredFoods();
            comp.setHasReceivedJournal(true); // any durable change

            assertTrue(comp.isPersistDue(0),
                    "waiting out a throttle before the very first write would leave a "
                            + "fresh player's progress in memory for no reason");
        }

        @Test
        @DisplayName("a burst of discoveries costs one write, not one per item")
        void throttledWithinTheInterval() {
            PlayerFoodComponent comp = component();
            comp.setHasReceivedJournal(true);
            assertTrue(comp.isPersistDue(1000));

            comp.setHasReceivedJournal(false);
            assertFalse(comp.isPersistDue(1100), "still inside the throttle window");
            assertFalse(comp.isPersistDue(1590), "still inside the throttle window");
            assertTrue(comp.isPersistDue(1600), "and due again once it has passed");
        }

        @Test
        @DisplayName("a write is not repeated until something else changes")
        void staysQuietOnceWritten() {
            PlayerFoodComponent comp = component();
            comp.setHasReceivedJournal(true);
            assertTrue(comp.isPersistDue(0));

            assertFalse(comp.isPersistDue(10_000),
                    "the flag was consumed by the save; an unchanged player must not be "
                            + "rewritten every interval forever");
        }
    }
}
