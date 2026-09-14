package net.tend1tnuy.florafare.client.tooltip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The icon row that replaced "Restores 3 hunger, 3.6 saturation".
 *
 * <p>That sentence was the widest thing Florafare put on a tooltip, and a tooltip is as
 * wide as its widest line — wide enough and vanilla stops being able to fit it on screen
 * at all, clamps it against the left edge and lets the right-hand side run off past its
 * own frame. The arithmetic here is what keeps the replacement short: how many icons a
 * value becomes, and the point past which a count replaces the row instead of growing it.
 */
class FoodValuesTooltipTest {

    @Nested
    @DisplayName("hunger icons")
    class Hunger {

        @Test
        @DisplayName("two hunger points are one whole drumstick")
        void evenValues() {
            assertEquals(1, FoodValuesTooltip.forValues(2, 0f).hungerIcons());
            assertEquals(3, FoodValuesTooltip.forValues(6, 0f).hungerIcons());
        }

        @Test
        @DisplayName("an odd value takes a whole icon, whose second half is left unfilled")
        void oddValues() {
            // A carrot: 3 hunger is one full drumstick and one half, so two icons wide.
            assertEquals(2, FoodValuesTooltip.forValues(3, 0f).hungerIcons());
        }

        @Test
        @DisplayName("past ten icons the row collapses to a single icon and a count")
        void overflowCollapses() {
            FoodValuesTooltip huge = FoodValuesTooltip.forValues(60, 0f);
            assertEquals(1, huge.hungerIcons(),
                    "thirty drumsticks would be exactly the runaway width this replaced");
            assertEquals("x30", huge.hungerOverflow());
        }

        @Test
        @DisplayName("ten icons is still drawn out in full")
        void atTheLimit() {
            FoodValuesTooltip atLimit = FoodValuesTooltip.forValues(20, 0f);
            assertEquals(10, atLimit.hungerIcons());
            assertNull(atLimit.hungerOverflow());
        }
    }

    @Nested
    @DisplayName("saturation segments")
    class Saturation {

        @Test
        @DisplayName("a fractional value still gets the segment it partly fills")
        void roundsUpToASegment() {
            // A carrot restores 3.6 saturation: one full segment and most of a second.
            assertEquals(2, FoodValuesTooltip.forValues(3, 3.6f).saturationSegments());
        }

        @Test
        @DisplayName("no saturation means no second row at all")
        void noneMeansNoRow() {
            FoodValuesTooltip none = FoodValuesTooltip.forValues(4, 0f);
            assertEquals(0, none.saturationSegments());
            assertNull(none.saturationOverflow());
        }

        @Test
        @DisplayName("saturation overflows to a count on the same terms as hunger")
        void overflowCollapses() {
            FoodValuesTooltip huge = FoodValuesTooltip.forValues(4, 48f);
            assertEquals(1, huge.saturationSegments());
            assertEquals("x24", huge.saturationOverflow());
        }
    }

    @Nested
    @DisplayName("layout")
    class Layout {

        @Test
        @DisplayName("the row is short even for a generous food")
        void staysNarrow() {
            // Ten icons at nine pixels is 90 — against the ~190 pixels the sentence it
            // replaced needed, and well inside any GUI scale's screen width.
            assertTrue(FoodValuesTooltip.forValues(20, 0f).hungerIcons() * 9 <= 90);
        }

        @Test
        @DisplayName("a saturation row adds height, and its absence does not")
        void heightFollowsTheSaturationRow() {
            int withSaturation = FoodValuesTooltip.forValues(3, 3.6f).getHeight();
            int without        = FoodValuesTooltip.forValues(3, 0f).getHeight();
            assertTrue(withSaturation > without,
                    "the second row has to be paid for, or it draws over the line below");
        }

        @Test
        @DisplayName("negative values never produce a negative-sized row")
        void negativesAreClamped() {
            FoodValuesTooltip negative = FoodValuesTooltip.forValues(-4, -2f);
            assertEquals(0, negative.hungerIcons());
            assertEquals(0, negative.saturationSegments());
            assertTrue(negative.getHeight() > 0);
        }
    }
}
