package net.tend1tnuy.florafare.client;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The item tooltip, the HUD hover tooltip, the journal page and the EMI panel all print
 * the same buff, and they only agree because they share these formatters. The numbers
 * they produce are what a player balances decisions on, so they are behaviour.
 */
class BuffDescriptionTest {

    @Nested
    @DisplayName("durations")
    class Durations {

        @Test
        @DisplayName("ticks below an hour become mm:ss")
        void formatsTicks() {
            assertEquals("05:00", BuffDescription.formatDuration(6000));
            assertEquals("00:10", BuffDescription.formatDuration(200));
            assertEquals("01:00", BuffDescription.formatDuration(1200));
            assertEquals("59:59", BuffDescription.formatDuration(71_980));
        }

        @Test
        @DisplayName("a partial second truncates rather than rounding up")
        void truncatesPartialSeconds() {
            assertEquals("00:00", BuffDescription.formatDuration(19));
            assertEquals("00:01", BuffDescription.formatDuration(39));
        }

        @Test
        @DisplayName("an expired buff reads as zero, never as a negative time")
        void clampsNegatives() {
            assertEquals("00:00", BuffDescription.formatDuration(-40));
            assertEquals("00:00", BuffDescription.formatDuration(-2));
        }

        @Test
        @DisplayName("an hour and up reads as h:mm:ss, not as a pile of minutes")
        void hoursAppear() {
            assertEquals("1:00:00", BuffDescription.formatDuration(72_000));
            assertEquals("1:30:05", BuffDescription.formatDuration(108_100));
            // ActiveFoodBuff.MAX_DURATION_TICKS: a full day, the longest buff there is.
            assertEquals("24:00:00", BuffDescription.formatDuration(1_728_000));
        }

        @Test
        @DisplayName("vanilla's infinite duration (-1) is the infinity sign")
        void infinite() {
            assertEquals("\u221E", BuffDescription.formatDuration(-1));
        }

        @Test
        @DisplayName("digits are ASCII whatever the default locale")
        void localeIndependent() {
            java.util.Locale previous = java.util.Locale.getDefault();
            try {
                // Arabic with native digits: String.format("%02d") without a locale
                // writes digits the game font cannot draw.
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"));
                assertEquals("05:00", BuffDescription.formatDuration(6000));
                assertEquals("1:00:00", BuffDescription.formatDuration(72_000));
            } finally {
                java.util.Locale.setDefault(previous);
            }
        }
    }

    @Nested
    @DisplayName("numbers")
    class Numbers {

        @Test
        @DisplayName("whole values lose the trailing .0")
        void trimsWholeNumbers() {
            assertEquals("4", BuffDescription.trimFloat(4.0f));
            assertEquals("0", BuffDescription.trimFloat(0.0f));
            assertEquals("-2", BuffDescription.trimFloat(-2.0f));
        }

        @Test
        @DisplayName("fractional values keep two decimals at most")
        void trimsFractions() {
            assertEquals("1.6", BuffDescription.trimFloat(1.6f));
            assertEquals("0.25", BuffDescription.trimFloat(0.25f));
            assertEquals("2", BuffDescription.trimFloat(1.999f), "rounds to a whole number, then drops the .0");
        }

        @Test
        @DisplayName("positive amounts carry a plus, negatives a real minus sign")
        void signsAmounts() {
            assertEquals("+2", BuffDescription.signed(2.0));
            assertEquals(MINUS + "2", BuffDescription.signed(-2.0));
            assertEquals("0", BuffDescription.signed(0.0));
            assertEquals("0", BuffDescription.signed(-0.0001), "no \"-0\" for a value that rounds away");
        }
    }

    private static final String MINUS = BuffDescription.MINUS;
    private static final String TIMES = BuffDescription.TIMES;

    @Nested
    @DisplayName("attribute amounts")
    class AttributeAmounts {

        @Test
        @DisplayName("add_multiplied_base reads as a percentage")
        void baseIsPercent() {
            assertEquals("+25%", BuffDescription.formatAmount(0.25, "add_multiplied_base"));
            assertEquals(MINUS + "15%", BuffDescription.formatAmount(-0.15, "add_multiplied_base"));
        }

        @Test
        @DisplayName("add_multiplied_total reads as a factor, so it cannot be mistaken for the base form")
        void totalIsFactor() {
            assertEquals(TIMES + "1.1", BuffDescription.formatAmount(0.1, "add_multiplied_total"));
            assertEquals(TIMES + "0.85", BuffDescription.formatAmount(-0.15, "add_multiplied_total"));
            assertEquals(TIMES + "2", BuffDescription.formatAmount(1.0, "add_multiplied_total"));
        }

        @Test
        @DisplayName("operation names are case-insensitive, like the parser the game uses")
        void caseInsensitive() {
            // contains("multiplied") used to miss these, and printed "+0.1" for +10%.
            assertEquals("+10%", BuffDescription.formatAmount(0.1, "ADD_MULTIPLIED_BASE"));
            assertEquals(TIMES + "1.1", BuffDescription.formatAmount(0.1, " Add_Multiplied_Total "));
            assertEquals("+2", BuffDescription.formatAmount(2.0, "ADD_VALUE"));
        }

        @Test
        @DisplayName("the pre-1.20.5 aliases display as the operation they apply as")
        void legacyAliases() {
            assertEquals("+10%", BuffDescription.formatAmount(0.1, "multiply_base"));
            assertEquals(TIMES + "1.1", BuffDescription.formatAmount(0.1, "MULTIPLY_TOTAL"));
            assertEquals("+2", BuffDescription.formatAmount(2.0, "addition"));
            assertEquals("+2", BuffDescription.formatAmount(2.0, "add"));
        }

        @Test
        @DisplayName("add_value stays a flat amount")
        void addValueIsFlat() {
            assertEquals("+2", BuffDescription.formatAmount(2.0, "add_value"));
            assertEquals(MINUS + "1.5", BuffDescription.formatAmount(-1.5, "add_value"));
        }

        @Test
        @DisplayName("an unknown operation is shown flat rather than guessed at")
        void unknownOperationIsFlat() {
            // The loaders refuse an unknown operation, so only a config built in code can
            // carry one; it is shown as the plain number it is.
            assertEquals("+2", BuffDescription.formatAmount(2.0, "nonsense"));
            assertEquals("+2", BuffDescription.formatAmount(2.0, null));
        }
    }

    @Nested
    @DisplayName("attribute names")
    class AttributeNames {

        private static String key(String id, Set<String> translated, String registryKey) {
            return BuffDescription.attributeNameKey(Identifier.of(id), translated::contains, registryKey);
        }

        @Test
        @DisplayName("a modded attribute uses its own translation, not a label for its path")
        void moddedUsesRegistryKey() {
            // critical_strike:chance used to resolve "florafare.attribute.chance" — any
            // mod's *:chance shared that one label.
            Set<String> translated = Set.of("florafare.attribute.chance",
                    "attribute.name.critical_strike.chance");
            assertEquals("attribute.name.critical_strike.chance",
                    key("critical_strike:chance", translated, "attribute.name.critical_strike.chance"));
        }

        @Test
        @DisplayName("a full-id Florafare label beats the mod's own")
        void fullIdOverrideWins() {
            Set<String> translated = Set.of("florafare.attribute.spell_power.fire",
                    "attribute.name.spell_power.fire");
            assertEquals("florafare.attribute.spell_power.fire",
                    key("spell_power:fire", translated, "attribute.name.spell_power.fire"));
        }

        @Test
        @DisplayName("two namespaces with the same path get different labels")
        void noPathCollisions() {
            Set<String> translated = Set.of("florafare.attribute.spell_power.haste",
                    "attribute.name.ranged_weapon.haste");
            assertEquals("florafare.attribute.spell_power.haste",
                    key("spell_power:haste", translated, "attribute.name.spell_power.haste"));
            assertEquals("attribute.name.ranged_weapon.haste",
                    key("ranged_weapon:haste", translated, "attribute.name.ranged_weapon.haste"));
        }

        @Test
        @DisplayName("vanilla ids keep the old path-keyed labels")
        void vanillaKeepsLegacyKeys() {
            Set<String> translated = Set.of("florafare.attribute.movement_speed",
                    "florafare.attribute.player.block_break_speed",
                    "attribute.name.generic.movement_speed");
            assertEquals("florafare.attribute.movement_speed",
                    key("minecraft:generic.movement_speed", translated, "attribute.name.generic.movement_speed"));
            assertEquals("florafare.attribute.player.block_break_speed",
                    key("minecraft:player.block_break_speed", translated, null));
        }

        @Test
        @DisplayName("a path-keyed label is never borrowed by a modded attribute")
        void legacyKeysAreVanillaOnly() {
            Set<String> translated = Set.of("florafare.attribute.armor");
            assertNull(key("somemod:armor", translated, null));
        }

        @Test
        @DisplayName("with no registry, the conventional and vanilla keys still resolve")
        void conventionalKeys() {
            assertEquals("attribute.name.spell_power.arcane",
                    key("spell_power:arcane", Set.of("attribute.name.spell_power.arcane"), null));
            assertEquals("attribute.name.generic.luck",
                    key("minecraft:generic.luck", Set.of("attribute.name.generic.luck"), null));
        }

        @Test
        @DisplayName("an untranslated attribute reads as \"namespace: path\"")
        void readableFallback() {
            assertEquals("critical_strike: chance",
                    BuffDescription.fallbackAttributeName(Identifier.of("critical_strike", "chance")));
            assertEquals("spell_power: resistance generic",
                    BuffDescription.fallbackAttributeName(Identifier.of("spell_power", "resistance.generic")));
            assertEquals("movement speed",
                    BuffDescription.fallbackAttributeName(Identifier.of("minecraft", "generic.movement_speed")));
        }
    }

    @Nested
    @DisplayName("effect levels")
    class EffectLevels {

        @Test
        @DisplayName("amplifier 0 is level I and stays unwritten, like vanilla")
        void levelOneIsBlank() {
            assertEquals("", BuffDescription.amplifierNumeral(0));
        }

        @Test
        @DisplayName("amplifiers 1-7 are roman numerals")
        void romanNumerals() {
            assertEquals(" II", BuffDescription.amplifierNumeral(1));
            assertEquals(" V", BuffDescription.amplifierNumeral(4));
            assertEquals(" VIII", BuffDescription.amplifierNumeral(7));
        }

        @Test
        @DisplayName("past VIII it falls back to arabic, also like vanilla")
        void arabicBeyondEight() {
            assertEquals(" 10", BuffDescription.amplifierNumeral(9));
        }
    }
}
