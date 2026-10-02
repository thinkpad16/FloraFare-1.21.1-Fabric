package net.tend1tnuy.florafare.component;

import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.component.PlayerFoodComponent.AftermathWindow;
import net.tend1tnuy.florafare.component.PlayerFoodComponent.EndCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The aftermath rules that need no player: which endings start one, what a second
 * ending of the same source does to one already running, and the modifier ids it writes.
 */
class AftermathRulesTest {

    @Nested
    @DisplayName("when an aftermath starts")
    class Triggers {

        @Test
        @DisplayName("running out starts it, for a buff and for a synergy alike")
        void expiry() {
            assertTrue(PlayerFoodComponent.startsAftermath(EndCause.EXPIRED));
        }

        @Test
        @DisplayName("a synergy losing a requirement starts it")
        void requirementLost() {
            assertTrue(PlayerFoodComponent.startsAftermath(EndCause.REQUIREMENT_LOST));
        }

        @Test
        @DisplayName("removing one buff early starts it — Forgotten Mead is not a hangover cure")
        void removal() {
            assertTrue(PlayerFoodComponent.startsAftermath(EndCause.REMOVED));
        }

        @Test
        @DisplayName("a wipe (clear, the API's clearBuffs, death) starts none")
        void clear() {
            assertFalse(PlayerFoodComponent.startsAftermath(EndCause.CLEARED));
        }

        @Test
        @DisplayName("switching synergies off server-wide starts none")
        void synergiesDisabled() {
            assertFalse(PlayerFoodComponent.startsAftermath(EndCause.SYNERGIES_DISABLED));
        }
    }

    @Nested
    @DisplayName("when the same source ends again")
    class Refresh {

        @Test
        @DisplayName("a fresh aftermath at least as long restarts it at full")
        void longerRestarts() {
            AftermathWindow next = PlayerFoodComponent.refreshAftermath(1000, 6000, 6000);
            assertEquals(6000, next.remaining());
            assertEquals(6000, next.initial());
        }

        @Test
        @DisplayName("a shorter one leaves the running aftermath exactly where it is")
        void shorterKeepsTheRunningOne() {
            AftermathWindow next = PlayerFoodComponent.refreshAftermath(5000, 6000, 1200);
            assertEquals(5000, next.remaining(), "never shortened by a briefer second ending");
            assertEquals(6000, next.initial(), "and the bar keeps counting from its own start");
        }

        @Test
        @DisplayName("it never stacks: the result is one window, never the sum")
        void neverStacks() {
            AftermathWindow next = PlayerFoodComponent.refreshAftermath(3000, 6000, 6000);
            assertEquals(6000, next.remaining());
        }

        @Test
        @DisplayName("an absurd duration is held to the buff ceiling")
        void clamped() {
            AftermathWindow next = PlayerFoodComponent.refreshAftermath(10, 10, Integer.MAX_VALUE);
            assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, next.remaining());
        }
    }

    @Test
    @DisplayName("an aftermath entry keeps its synergy flag through NBT, outside the synergy list")
    void entryPersists() {
        ActiveFoodBuff entry = new ActiveFoodBuff("mypack:arcane_overflow", "minecraft:golden_apple",
                4000, 6000, true);
        entry.addModifierRecord(Identifier.of("florafare", PlayerFoodComponent.aftermathAttributePath(
                        PlayerFoodComponent.aftermathScope("mypack:arcane_overflow", true),
                        Identifier.of("minecraft", "generic.max_health"), 0)),
                Identifier.of("minecraft", "generic.max_health"), -0.25, "add_multiplied_total");

        ActiveFoodBuff read = ActiveFoodBuff.fromNbt(entry.toNbt());
        assertTrue(read.isSynergy());
        assertEquals(4000, read.getDurationRemaining());
        assertEquals(6000, read.getInitialDuration());
        assertEquals(entry.getAppliedModifiers(), read.getAppliedModifiers());
    }

    @Nested
    @DisplayName("modifier ids")
    class ModifierIds {

        private final Identifier maxHealth = Identifier.of("minecraft", "generic.max_health");

        @Test
        @DisplayName("never collide with the buff's own ids, whatever the target")
        void distinctFromBuffIds() {
            ActiveFoodBuff buff = new ActiveFoodBuff("minecraft:honey_bottle", "minecraft:honey_bottle",
                    600, 600);
            String buffScope = PlayerFoodComponent.modifierScope(buff, false);
            String aftermathScope = PlayerFoodComponent.aftermathScope("minecraft:honey_bottle", false);

            assertNotEquals(PlayerFoodComponent.attributeModifierPath(buffScope, maxHealth, 0),
                    PlayerFoodComponent.aftermathAttributePath(aftermathScope, maxHealth, 0));
            assertNotEquals("health_" + buffScope, PlayerFoodComponent.aftermathHealthPath(aftermathScope));
            assertTrue(PlayerFoodComponent.aftermathAttributePath(aftermathScope, maxHealth, 0)
                    .startsWith(PlayerFoodComponent.AFTERMATH_MODIFIER_PREFIX));
        }

        @Test
        @DisplayName("a synergy and a food target with the same id get different ids")
        void synergyAndBuffApart() {
            assertNotEquals(PlayerFoodComponent.aftermathScope("mypack:feast", true),
                    PlayerFoodComponent.aftermathScope("mypack:feast", false));
        }

        @Test
        @DisplayName("two items under one tag share their aftermath's ids, so it refreshes instead of stacking")
        void oneScopePerSource() {
            ActiveFoodBuff apple = new ActiveFoodBuff("#c:foods/fruit", "minecraft:apple", 600, 600);
            ActiveFoodBuff berry = new ActiveFoodBuff("#c:foods/fruit", "minecraft:sweet_berries", 600, 600);
            assertNotEquals(PlayerFoodComponent.modifierScope(apple, false),
                    PlayerFoodComponent.modifierScope(berry, false), "the buffs are two slots");
            assertEquals(PlayerFoodComponent.aftermathScope(apple.getTarget(), false),
                    PlayerFoodComponent.aftermathScope(berry.getTarget(), false), "the aftermath is one");
        }

        @Test
        @DisplayName("every id is a valid identifier path, even for a malformed target")
        void validPaths() {
            String scope = PlayerFoodComponent.aftermathScope("#Weird Pack:Spaced Tag", false);
            assertDoesNotThrow(() -> Identifier.of("florafare",
                    PlayerFoodComponent.aftermathAttributePath(scope, maxHealth, 3)));
            assertDoesNotThrow(() -> Identifier.of("florafare",
                    PlayerFoodComponent.aftermathHealthPath(scope)));
        }
    }
}
