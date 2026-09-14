package net.tend1tnuy.florafare.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        @DisplayName("ticks become mm:ss")
        void formatsTicks() {
            assertEquals("05:00", BuffDescription.mmss(6000));
            assertEquals("00:10", BuffDescription.mmss(200));
            assertEquals("01:00", BuffDescription.mmss(1200));
        }

        @Test
        @DisplayName("a partial second truncates rather than rounding up")
        void truncatesPartialSeconds() {
            assertEquals("00:00", BuffDescription.mmss(19));
            assertEquals("00:01", BuffDescription.mmss(39));
        }

        @Test
        @DisplayName("an expired buff reads as zero, never as a negative time")
        void clampsNegatives() {
            assertEquals("00:00", BuffDescription.mmss(-40));
        }

        @Test
        @DisplayName("durations past an hour keep counting in minutes")
        void longDurations() {
            assertEquals("60:00", BuffDescription.mmss(72000));
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
        }

        @Test
        @DisplayName("positive amounts are explicitly signed, negatives keep their own sign")
        void signsAmounts() {
            assertEquals("+2", BuffDescription.signed(2.0));
            assertEquals("-2", BuffDescription.signed(-2.0));
            assertEquals("0", BuffDescription.signed(0.0));
        }
    }

    @Nested
    @DisplayName("attribute amounts")
    class AttributeAmounts {

        @Test
        @DisplayName("multiplied operations read as percentages")
        void multipliedIsPercent() {
            assertEquals("+10%", BuffDescription.formatAmount(0.1, "add_multiplied_total"));
            assertEquals("+25%", BuffDescription.formatAmount(0.25, "add_multiplied_base"));
        }

        @Test
        @DisplayName("a negative multiplied amount keeps its own sign")
        void negativePercent() {
            assertEquals("-15%", BuffDescription.formatAmount(-0.15, "add_multiplied_total"));
        }

        @Test
        @DisplayName("add_value stays a flat amount")
        void addValueIsFlat() {
            assertEquals("+2", BuffDescription.formatAmount(2.0, "add_value"));
            assertEquals("-1.5", BuffDescription.formatAmount(-1.5, "add_value"));
        }

        @Test
        @DisplayName("an unknown operation is treated as flat, matching how it is applied")
        void unknownOperationIsFlat() {
            // PlayerFoodComponent#mapOperation falls back to ADD_VALUE for anything it
            // does not recognise, so the display has to fall back the same way or the
            // tooltip would promise a percentage the game then applies as a flat value.
            assertEquals("+2", BuffDescription.formatAmount(2.0, "nonsense"));
            assertEquals("+2", BuffDescription.formatAmount(2.0, null));
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
