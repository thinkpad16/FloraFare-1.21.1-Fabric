package net.tend1tnuy.florafare.food;

import net.tend1tnuy.florafare.config.FlorafareConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Natural regeneration's arithmetic: the delay after damage, the curve, its cost, the config ranges. */
class RegenMathTest {

    private static final float EPS = 1e-4f;

    /** The shipped defaults: base 0.5, exponent 0.5, min hunger 6, curve 0.25, bonus 1, cost 6, vanilla saturation. */
    private static final RegenMath.Curve DEFAULTS = new RegenMath.Curve(0.5f, 0.5f, 6, 0.25f, 1.0f, 6.0f, 0f);

    // -------------------------------------------------------------------------
    // DELAY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the delay is seconds x 20 x the attribute, rounded to ticks")
    void delayScalesWithMultiplier() {
        assertEquals(40, RegenMath.delayTicks(2.0f, 1.0));
        assertEquals(20, RegenMath.delayTicks(2.0f, 0.5));
        assertEquals(60, RegenMath.delayTicks(2.0f, 1.5));
        assertEquals(1, RegenMath.delayTicks(0.06f, 1.0), "1.2 ticks rounds to 1");
        assertEquals(1_200_000, RegenMath.delayTicks(600f, 100.0), "the largest the ranges allow");
    }

    @Test
    @DisplayName("0 seconds, an attribute of 0, negatives and NaN all mean no delay")
    void zeroMeansNoDelay() {
        assertEquals(0, RegenMath.delayTicks(0f, 1.0));
        assertEquals(0, RegenMath.delayTicks(2.0f, 0.0));
        assertEquals(0, RegenMath.delayTicks(-1f, 1.0));
        assertEquals(0, RegenMath.delayTicks(2.0f, -0.5));
        assertEquals(0, RegenMath.delayTicks(Float.NaN, 1.0));
        assertEquals(0, RegenMath.delayTicks(2.0f, Double.NaN));
        assertEquals(Integer.MAX_VALUE, RegenMath.delayTicks(Float.MAX_VALUE, 100.0), "saturates, never overflows");
    }

    @Test
    @DisplayName("remaining ticks count down to 0 and never go negative")
    void remaining() {
        assertEquals(40, RegenMath.remainingTicks(1040, 1000));
        assertEquals(0, RegenMath.remainingTicks(1000, 1000));
        assertEquals(0, RegenMath.remainingTicks(500, 1000));
        assertEquals(0, RegenMath.remainingTicks(0, 1000), "never damaged");
    }

    @Test
    @DisplayName("a hit restarts the delay but never shortens a longer one; 0 changes nothing")
    void extending() {
        assertEquals(1040, RegenMath.extendedUntil(0, 1000, 40));
        assertEquals(1040, RegenMath.extendedUntil(1020, 1000, 40), "a fresh hit restarts the wait");
        assertEquals(2000, RegenMath.extendedUntil(2000, 1000, 40), "a longer delay is kept");
        assertEquals(1500, RegenMath.extendedUntil(1500, 1000, 0), "0 ticks (ignored damage) leaves it");
        assertEquals(1500, RegenMath.extendedUntil(1500, 1000, -5));
    }

    // -------------------------------------------------------------------------
    // CURVE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the hunger factor runs linearly from the curve value at the minimum to 1 at a full bar")
    void hungerFactor() {
        assertEquals(1f, RegenMath.hungerFactor(20, 6, 0.25f), EPS);
        assertEquals(0.25f, RegenMath.hungerFactor(6, 6, 0.25f), EPS);
        assertEquals(0.625f, RegenMath.hungerFactor(13, 6, 0.25f), EPS, "half way");
        assertEquals(0f, RegenMath.hungerFactor(5, 6, 0.25f), "below the minimum: no regeneration");
        assertEquals(1f, RegenMath.hungerFactor(20, 20, 0.25f), EPS, "a minimum of 20 is all or nothing");
        assertEquals(0f, RegenMath.hungerFactor(19, 20, 0.25f));
        assertEquals(0.625f, RegenMath.hungerFactor(10, 0, 0.25f), EPS, "minimum 0");
        assertTrue(RegenMath.hungerAllows(6, 6));
        assertFalse(RegenMath.hungerAllows(5, 6));
    }

    @Test
    @DisplayName("20 max health, full bar: 0.5 HP/s with no saturation, doubled at full saturation")
    void vanillaHealthBar() {
        assertEquals(0.5f, RegenMath.healPerSecond(DEFAULTS, 20f, 20, 0f, 1.0), EPS);
        assertEquals(0.75f, RegenMath.healPerSecond(DEFAULTS, 20f, 20, 10f, 1.0), EPS);
        assertEquals(1.0f, RegenMath.healPerSecond(DEFAULTS, 20f, 20, 20f, 1.0), EPS);
        assertEquals(0.25f, RegenMath.healPerInterval(DEFAULTS, 20f, 20, 0f, 1.0), EPS, "half a second's worth");
    }

    @Test
    @DisplayName("100 max health heals sqrt(5) times as many points per second at the default exponent")
    void bigHealthBar() {
        float expected = 0.5f * (float) Math.sqrt(5);
        assertEquals(expected, RegenMath.healPerSecond(DEFAULTS, 100f, 20, 0f, 1.0), EPS);

        RegenMath.Curve flat = new RegenMath.Curve(0.5f, 0f, 6, 0.25f, 1.0f, 6.0f, 0f);
        assertEquals(0.5f, RegenMath.healPerSecond(flat, 100f, 20, 0f, 1.0), EPS, "exponent 0: flat");
        RegenMath.Curve proportional = new RegenMath.Curve(0.5f, 1f, 6, 0.25f, 1.0f, 6.0f, 0f);
        assertEquals(2.5f, RegenMath.healPerSecond(proportional, 100f, 20, 0f, 1.0), EPS, "exponent 1: same share");
    }

    @Test
    @DisplayName("the hunger thresholds slow the curve down and stop it below the minimum")
    void hungerThresholds() {
        assertEquals(0.3125f, RegenMath.healPerSecond(DEFAULTS, 20f, 13, 0f, 1.0), EPS);
        assertEquals(0.125f, RegenMath.healPerSecond(DEFAULTS, 20f, 6, 0f, 1.0), EPS);
        assertEquals(0f, RegenMath.healPerSecond(DEFAULTS, 20f, 5, 20f, 1.0), "saturation does not help below it");
    }

    @Test
    @DisplayName("the saturation bonus is measured against maxSaturation when set, and capped at full")
    void saturationBonus() {
        RegenMath.Curve bigPool = new RegenMath.Curve(0.5f, 0.5f, 6, 0.25f, 1.0f, 6.0f, 40f);
        assertEquals(0.75f, RegenMath.healPerSecond(bigPool, 20f, 20, 20f, 1.0), EPS, "20 of 40 is half");
        assertEquals(1.0f, RegenMath.healPerSecond(bigPool, 20f, 20, 40f, 1.0), EPS);
        assertEquals(2f, RegenMath.saturationFactor(35f, 1f, 0f), EPS, "above the cap pays out as full");
        assertEquals(1f, RegenMath.saturationFactor(-3f, 1f, 0f), EPS);
        assertEquals(1f, RegenMath.saturationFactor(Float.NaN, 1f, 0f), EPS);
        assertEquals(20f, RegenMath.saturationCapForBonus(0f));
        assertEquals(40f, RegenMath.saturationCapForBonus(40f));
    }

    @Test
    @DisplayName("florafare:natural_regeneration multiplies the curve; 0 stops it")
    void attribute() {
        assertEquals(1.0f, RegenMath.healPerSecond(DEFAULTS, 20f, 20, 0f, 2.0), EPS);
        assertEquals(0f, RegenMath.healPerSecond(DEFAULTS, 20f, 20, 20f, 0.0));
        assertEquals(0f, RegenMath.healPerSecond(DEFAULTS, 0f, 20, 20f, 1.0), "no max health, no healing");
    }

    // -------------------------------------------------------------------------
    // COST
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a health point costs regenCostPerHealth at 20 max health, less at more")
    void exhaustionCost() {
        assertEquals(6f, RegenMath.exhaustionCost(1f, 6f, 20f, 0.5f), EPS);
        assertEquals(6f / (float) Math.sqrt(5), RegenMath.exhaustionCost(1f, 6f, 100f, 0.5f), EPS);
        assertEquals(0f, RegenMath.exhaustionCost(0f, 6f, 20f, 0.5f), "nothing healed, nothing charged");
        assertEquals(0f, RegenMath.exhaustionCost(1f, 0f, 20f, 0.5f));
    }

    @Test
    @DisplayName("a second of regeneration drains the same exhaustion at 20 and at 100 max health")
    void drainIndependentOfMaxHealth() {
        float perSecond20 = RegenMath.healPerSecond(DEFAULTS, 20f, 20, 0f, 1.0);
        float perSecond100 = RegenMath.healPerSecond(DEFAULTS, 100f, 20, 0f, 1.0);
        float drain20 = RegenMath.exhaustionCost(perSecond20, 6f, 20f, 0.5f);
        float drain100 = RegenMath.exhaustionCost(perSecond100, 6f, 100f, 0.5f);
        assertEquals(drain20, drain100, EPS);
        assertEquals(3f, drain20, EPS, "0.5 HP/s x 6");
    }

    @Test
    @DisplayName("exhaustionMultiplier scales additions only")
    void exhaustionMultiplier() {
        assertEquals(3f, RegenMath.scaleExhaustion(6f, 0.5f), EPS);
        assertEquals(0f, RegenMath.scaleExhaustion(6f, 0f), EPS);
        assertEquals(-2f, RegenMath.scaleExhaustion(-2f, 0f), EPS, "another mod taking exhaustion away");
    }

    // -------------------------------------------------------------------------
    // CONFIG
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("float settings are clamped into range, NaN becoming the default")
    void configClamps() {
        assertEquals(0f, FlorafareConfig.clampFloat(-1f, 0f, FlorafareConfig.MAX_REGEN_DELAY_SECONDS, 2f));
        assertEquals(FlorafareConfig.MAX_REGEN_DELAY_SECONDS,
                FlorafareConfig.clampFloat(1e9f, 0f, FlorafareConfig.MAX_REGEN_DELAY_SECONDS, 2f));
        assertEquals(2f, FlorafareConfig.clampFloat(Float.NaN, 0f, FlorafareConfig.MAX_REGEN_DELAY_SECONDS, 2f));
        assertEquals(1.5f, FlorafareConfig.clampFloat(1.5f, 0f, FlorafareConfig.MAX_EXHAUSTION_MULTIPLIER, 1f));
        assertEquals(1f, FlorafareConfig.clampFloat(3f, 0f, 1f, 0.25f), "regenHungerCurve tops out at 1");
    }

    @Test
    @DisplayName("regenMode reads \"vanilla\" and \"curve\" in any case, and nothing else")
    void regenModeParse() {
        assertEquals(FlorafareConfig.RegenMode.VANILLA, FlorafareConfig.RegenMode.parse("vanilla"));
        assertEquals(FlorafareConfig.RegenMode.CURVE, FlorafareConfig.RegenMode.parse(" Curve "));
        assertNull(FlorafareConfig.RegenMode.parse("hunger_tweaks"));
        assertNull(FlorafareConfig.RegenMode.parse(null));
        assertEquals("curve", FlorafareConfig.RegenMode.CURVE.configName());
    }
}
