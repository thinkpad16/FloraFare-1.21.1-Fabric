package net.tend1tnuy.florafare.component;

import net.tend1tnuy.florafare.config.FlorafareConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The buff slot count is written from five places — mod init, the server config packet,
 * the ModMenu screen, the disconnect restore, and the tests — and read on the hot path in
 * {@code tryAddBuff} and once per frame by the HUD.
 *
 * <p>As a bare public field none of those writers clamped it. Zero stops every buff from
 * ever applying with no message anywhere, and a negative value makes the HUD ask for an
 * array of negative length. A remote server can send either.
 */
class MaxBuffSlotsTest {

    @AfterEach
    void restoreDefault() {
        PlayerFoodComponent.setMaxBuffSlots(3);
    }

    @Test
    @DisplayName("a value in range is kept as it is")
    void acceptsValidValues() {
        PlayerFoodComponent.setMaxBuffSlots(5);
        assertEquals(5, PlayerFoodComponent.getMaxBuffSlots());
    }

    @Test
    @DisplayName("zero and negatives are raised to the minimum instead of disabling buffs")
    void clampsLow() {
        PlayerFoodComponent.setMaxBuffSlots(0);
        assertEquals(FlorafareConfig.MIN_BUFF_SLOTS, PlayerFoodComponent.getMaxBuffSlots());

        PlayerFoodComponent.setMaxBuffSlots(-4);
        assertEquals(FlorafareConfig.MIN_BUFF_SLOTS, PlayerFoodComponent.getMaxBuffSlots());
    }

    @Test
    @DisplayName("an absurd value is capped rather than believed")
    void clampsHigh() {
        PlayerFoodComponent.setMaxBuffSlots(Integer.MAX_VALUE);
        assertEquals(FlorafareConfig.MAX_BUFF_SLOTS, PlayerFoodComponent.getMaxBuffSlots());
    }

    @Test
    @DisplayName("the clamp agrees with the range the config file is held to")
    void agreesWithTheConfigRange() {
        PlayerFoodComponent.setMaxBuffSlots(FlorafareConfig.MIN_BUFF_SLOTS);
        assertEquals(FlorafareConfig.MIN_BUFF_SLOTS, PlayerFoodComponent.getMaxBuffSlots());
        PlayerFoodComponent.setMaxBuffSlots(FlorafareConfig.MAX_BUFF_SLOTS);
        assertEquals(FlorafareConfig.MAX_BUFF_SLOTS, PlayerFoodComponent.getMaxBuffSlots());
    }
}
