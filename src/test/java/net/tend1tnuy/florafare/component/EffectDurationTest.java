package net.tend1tnuy.florafare.component;

import net.minecraft.entity.effect.StatusEffectInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Datapack effect durations as they reach {@link StatusEffectInstance}.
 *
 * <p>The tooltip shows {@code -1} as ∞, but the runtime used to clamp every negative to 0
 * ticks, so an "infinite" effect was never applied at all.
 */
class EffectDurationTest {

    @Test
    @DisplayName("-1 stays vanilla's infinite duration")
    void infinitePassesThrough() {
        assertEquals(StatusEffectInstance.INFINITE, PlayerFoodComponent.effectDurationTicks(-1));
    }

    @Test
    @DisplayName("zero and other negatives mean no effect")
    void nonPositiveIsSkipped() {
        assertEquals(0, PlayerFoodComponent.effectDurationTicks(0));
        assertEquals(0, PlayerFoodComponent.effectDurationTicks(-20));
    }

    @Test
    @DisplayName("ordinary durations pass and huge ones are capped at the buff ceiling")
    void positiveIsClamped() {
        assertEquals(600, PlayerFoodComponent.effectDurationTicks(600));
        assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, PlayerFoodComponent.effectDurationTicks(Integer.MAX_VALUE));
    }
}
