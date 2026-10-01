package net.tend1tnuy.florafare.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a running synergy becomes when synergies are re-evaluated.
 *
 * <p>Every bite of any food re-evaluates them, and that used to re-sync each running
 * synergy to its full window — so a synergy capped at ten seconds over five-minute
 * ingredients was reset to ten seconds on every bite, and ran for as long as the player
 * kept eating. Only a bite that feeds the synergy may move its end later now.
 */
class SynergyRefreshTest {

    /** A synergy capped at 200 ticks over ingredients with 6000 left. */
    private static final PlayerFoodComponent.SynergyWindow CAPPED =
            PlayerFoodComponent.synergyWindow(6000, 6000, 200);

    @Test
    @DisplayName("an unrelated bite does not reset a capped synergy")
    void unrelatedBiteDoesNotReset() {
        assertNull(PlayerFoodComponent.refreshWindow(100, 200, CAPPED, false),
                "the synergy is half-way through its cap and must stay there");
    }

    @Test
    @DisplayName("eating one of its ingredients again restarts it at the cap")
    void fedBiteRestarts() {
        PlayerFoodComponent.SynergyWindow next =
                PlayerFoodComponent.refreshWindow(100, 200, CAPPED, true);
        assertNotNull(next);
        assertEquals(200, next.remaining());
        assertEquals(200, next.initial());
    }

    @Test
    @DisplayName("an unfed re-evaluation can still bring the end forward to the shortest ingredient")
    void canShorten() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(500, 6000, 0);
        PlayerFoodComponent.SynergyWindow next =
                PlayerFoodComponent.refreshWindow(3000, 6000, window, false);
        assertNotNull(next);
        assertEquals(500, next.remaining());
        assertEquals(6000, next.initial(), "brought forward, not restarted");
    }

    @Test
    @DisplayName("an unfed re-evaluation never lengthens a synergy")
    void neverLengthensUnfed() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(5000, 6000, 0);
        assertNull(PlayerFoodComponent.refreshWindow(1000, 6000, window, false));
    }

    @Test
    @DisplayName("drift within the tolerance is left alone either way")
    void toleratesDrift() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(1000, 6000, 0);
        assertNull(PlayerFoodComponent.refreshWindow(
                1000 + PlayerFoodComponent.SYNERGY_DRIFT_TOLERANCE, 6000, window, false));
        assertNull(PlayerFoodComponent.refreshWindow(
                1000 - PlayerFoodComponent.SYNERGY_DRIFT_TOLERANCE, 6000, window, true));
    }

    @Test
    @DisplayName("a fed refresh of an uncapped synergy follows its ingredients forward")
    void fedUncappedFollows() {
        PlayerFoodComponent.SynergyWindow window =
                PlayerFoodComponent.synergyWindow(4000, 6000, 0);
        PlayerFoodComponent.SynergyWindow next =
                PlayerFoodComponent.refreshWindow(1000, 6000, window, true);
        assertNotNull(next);
        assertEquals(4000, next.remaining());
    }
}
