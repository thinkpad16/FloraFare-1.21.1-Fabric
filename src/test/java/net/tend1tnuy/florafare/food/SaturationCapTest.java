package net.tend1tnuy.florafare.food;

import net.tend1tnuy.florafare.config.FlorafareConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code maxSaturation} ceiling and how the config keeps it in range. */
class SaturationCapTest {

    @Test
    @DisplayName("0 keeps vanilla's ceiling: the hunger level after the bite")
    void zeroIsVanilla() {
        assertEquals(14f, SaturationCap.ceiling(0f, 14f));
        assertEquals(20f, SaturationCap.ceiling(0f, 20f));
    }

    @Test
    @DisplayName("a configured ceiling replaces the hunger level, above or below it")
    void configuredReplacesHungerLevel() {
        assertEquals(40f, SaturationCap.ceiling(40f, 12f));
        assertEquals(10f, SaturationCap.ceiling(10f, 20f));
    }

    @Test
    @DisplayName("negative and NaN values mean vanilla, huge ones stop at the upper bound")
    void configClamp() {
        assertEquals(0f, FlorafareConfig.clampMaxSaturation(-5f));
        assertEquals(0f, FlorafareConfig.clampMaxSaturation(Float.NaN));
        assertEquals(40f, FlorafareConfig.clampMaxSaturation(40f));
        assertEquals(FlorafareConfig.MAX_SATURATION_CEILING, FlorafareConfig.clampMaxSaturation(1e9f));
    }
}
