package net.tend1tnuy.florafare.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How long a synergy runs for.
 *
 * <p>A synergy mirrors the shortest of the buffs feeding it, capped by its own
 * {@code duration} field. The cap is the part worth pinning down: it is applied on both
 * the create and the refresh path, and the refresh path re-applies the synergy's effects
 * — so if the cap could ever lengthen rather than shorten, refreshing would be a way to
 * extend a synergy indefinitely.
 */
class SynergyWindowTest {

    private static final int NO_CEILING = 0;

    @Test
    @DisplayName("with no ceiling the window is the shortest contributing buff")
    void mirrorsShortestBuff() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(900, 3600, NO_CEILING);
        assertEquals(900, window.remaining());
        assertEquals(3600, window.initial());
    }

    @Test
    @DisplayName("a negative ceiling means no ceiling, same as zero")
    void negativeCeilingIsNoCeiling() {
        assertEquals(900, PlayerFoodComponent.synergyWindow(900, 3600, -1).remaining());
    }

    @Test
    @DisplayName("a ceiling below the shortest buff shortens the window")
    void ceilingShortens() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(6000, 6000, 200);
        assertEquals(200, window.remaining());
        assertEquals(200, window.initial(),
                "the progress bar has to count down from the capped length, not the "
                        + "uncapped one, or a capped synergy renders as permanently full");
    }

    @Test
    @DisplayName("a ceiling above the shortest buff changes nothing")
    void ceilingNeverLengthens() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(300, 1200, 6000);
        assertEquals(300, window.remaining(),
                "the cap is a ceiling; it must never hand a synergy more time than its "
                        + "ingredients have left");
        assertEquals(1200, window.initial());
    }

    @Test
    @DisplayName("applying the window twice is stable, so refreshing cannot extend it")
    void idempotent() {
        PlayerFoodComponent.SynergyWindow once =
                PlayerFoodComponent.synergyWindow(6000, 6000, 200);
        PlayerFoodComponent.SynergyWindow twice =
                PlayerFoodComponent.synergyWindow(once.remaining(), once.initial(), 200);
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("a ceiling past the hard duration cap is clamped like any other duration")
    void ceilingIsItselfClamped() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MAX_VALUE);
        assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS, window.remaining());
    }

    @Test
    @DisplayName("initial is never below remaining, whatever the caller passes")
    void initialNeverBelowRemaining() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(1200, 600, NO_CEILING);
        assertTrue(window.initial() >= window.remaining(),
                "ActiveFoodBuff enforces this too, but a window that violates it means the "
                        + "HUD divides by a smaller number than it counts down from");
    }
}
