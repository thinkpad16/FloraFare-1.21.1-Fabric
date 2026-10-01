package net.tend1tnuy.florafare.command;

import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The one set of ranges {@code setbuff} and {@code edit} now share. */
class CommandBoundsTest {

    @Test
    @DisplayName("duration 0 (hunger-only) up to the runtime's own cap")
    void duration() {
        assertEquals(0, CommandBounds.MIN_DURATION);
        assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, CommandBounds.MAX_DURATION);
        assertTrue(CommandBounds.inBounds(0, 4, 0.6, 0));
        assertFalse(CommandBounds.inBounds(-1, 4, 0.6, 0));
        assertFalse(CommandBounds.inBounds(ActiveFoodBuff.MAX_DURATION_TICKS + 1, 4, 0.6, 0));
    }

    @Test
    @DisplayName("negative nutrition, saturation and health are allowed within range")
    void negatives() {
        assertTrue(CommandBounds.inBounds(1200, -20, -5.0, -1024.0));
        assertTrue(CommandBounds.inBounds(1200, 20, 5.0, 1024.0));
        assertFalse(CommandBounds.inBounds(1200, -21, 0, 0));
        assertFalse(CommandBounds.inBounds(1200, 21, 0, 0));
        assertFalse(CommandBounds.inBounds(1200, 0, 5.5, 0));
        assertFalse(CommandBounds.inBounds(1200, 0, 0, 2000));
    }
}
