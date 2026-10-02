package net.tend1tnuy.florafare.food;

/**
 * The arithmetic of natural regeneration: the delay after damage, the regeneration curve
 * and what healing on it costs. Pure, so it is unit-tested without a game; the game side
 * (who is delayed, when to heal, the hooks) lives in {@link NaturalRegeneration}.
 */
public final class RegenMath {

    private RegenMath() {}

    public static final int TICKS_PER_SECOND = 20;

    /** How often the curve heals, in ticks: the same cadence as vanilla's fast regeneration. */
    public static final int CURVE_INTERVAL_TICKS = 10;

    /** The health and hunger values the curve is normalized against: vanilla's full bars. */
    public static final float VANILLA_MAX_HEALTH = 20f;
    public static final int   FULL_HUNGER        = 20;

    /**
     * The saturation the curve's saturation bonus is measured against when
     * {@code maxSaturation} is vanilla (0): saturation never exceeds the hunger bar there.
     */
    public static final float VANILLA_SATURATION_CAP = 20f;

    // -------------------------------------------------------------------------
    // DELAY AFTER DAMAGE
    // -------------------------------------------------------------------------

    /**
     * How long natural regeneration waits after a hit, in ticks.
     *
     * @param seconds    {@code regenDelayAfterDamage}; 0 or less (or NaN) means no delay
     * @param multiplier the player's {@code florafare:regen_delay_multiplier}; 0 or less
     *                   (or NaN) means no delay
     * @return the delay in ticks, rounded to the nearest tick, 0 for none; never negative
     *         and never past {@link Integer#MAX_VALUE}
     */
    public static int delayTicks(float seconds, double multiplier) {
        // Written as !(x > 0) so NaN lands on "no delay" too.
        if (!(seconds > 0f) || !(multiplier > 0.0)) return 0;
        double ticks = (double) seconds * TICKS_PER_SECOND * multiplier;
        if (ticks >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.round(ticks);
    }

    /**
     * The ticks left of a delay that ends at {@code untilTick}, measured at {@code nowTick}.
     *
     * @return 0 once it has run out; at most {@link Integer#MAX_VALUE}
     */
    public static int remainingTicks(long untilTick, long nowTick) {
        long left = untilTick - nowTick;
        if (left <= 0) return 0;
        return (int) Math.min(left, Integer.MAX_VALUE);
    }

    /**
     * The tick a delay of {@code ticks} starting now ends at, kept if an already running
     * delay ends later: a small hit never cuts short a long delay an addon set.
     *
     * @param currentUntil the tick the running delay ends at (anything in the past for none)
     */
    public static long extendedUntil(long currentUntil, long nowTick, int ticks) {
        if (ticks <= 0) return currentUntil;
        return Math.max(currentUntil, nowTick + ticks);
    }

    // -------------------------------------------------------------------------
    // THE REGENERATION CURVE
    // -------------------------------------------------------------------------

    /**
     * The settings that shape the curve, in one place so a heal reads all of them from the
     * same moment. Mirrors the {@code regen...} config fields one for one, plus
     * {@code maxSaturation}, which decides what "full saturation" means.
     *
     * @param base               health per second at 20 max health, full hunger and no
     *                           saturation ({@code regenBase})
     * @param maxHealthExponent  how strongly the rate grows with max health
     *                           ({@code regenMaxHealthExponent}): 0 heals the same flat
     *                           amount at any max health, 1 the same fraction of it
     * @param minHunger          the hunger level the curve stops at ({@code regenMinHunger})
     * @param hungerCurve        the rate factor at {@code minHunger}, rising linearly to 1 at
     *                           a full bar ({@code regenHungerCurve})
     * @param saturationBonus    extra rate at full saturation, as a fraction
     *                           ({@code regenSaturationBonus}: 1 = twice as fast)
     * @param costPerHealth      exhaustion per health point healed at 20 max health
     *                           ({@code regenCostPerHealth})
     * @param maxSaturation      the {@code maxSaturation} setting; 0 means vanilla's 20
     */
    public record Curve(float base, float maxHealthExponent, int minHunger, float hungerCurve,
                        float saturationBonus, float costPerHealth, float maxSaturation) {}

    /**
     * Whether the curve heals at all at this hunger level: at or above {@code minHunger}.
     */
    public static boolean hungerAllows(int foodLevel, int minHunger) {
        return foodLevel >= minHunger;
    }

    /**
     * The rate factor for the hunger bar: {@code hungerCurve} at {@code minHunger}, rising
     * linearly to 1.0 at a full bar, and 0 below {@code minHunger}.
     */
    public static float hungerFactor(int foodLevel, int minHunger, float hungerCurve) {
        if (!hungerAllows(foodLevel, minHunger)) return 0f;
        if (foodLevel >= FULL_HUNGER || minHunger >= FULL_HUNGER) return 1f;
        float progress = (foodLevel - minHunger) / (float) (FULL_HUNGER - minHunger);
        return hungerCurve + (1f - hungerCurve) * progress;
    }

    /**
     * The saturation the bonus counts as "full": {@code maxSaturation} when it is set,
     * vanilla's 20 otherwise.
     */
    public static float saturationCapForBonus(float maxSaturation) {
        return maxSaturation > 0f ? maxSaturation : VANILLA_SATURATION_CAP;
    }

    /**
     * The rate factor for saturation: {@code 1 + bonus x saturation / cap}, the fraction
     * held between 0 and 1 so a value above the cap (a ceiling lowered mid-game) never
     * pays out more than full.
     */
    public static float saturationFactor(float saturation, float saturationBonus, float maxSaturation) {
        float fraction = saturation / saturationCapForBonus(maxSaturation);
        if (!(fraction > 0f)) fraction = 0f;
        if (fraction > 1f) fraction = 1f;
        return 1f + saturationBonus * fraction;
    }

    /**
     * {@code (maxHealth / 20) ^ exponent}: how the rate scales with max health. A player
     * with five times the hearts heals {@code 5^0.5 ~ 2.24x} as many points per second at
     * the default exponent — faster in points, slower as a share of the bar.
     */
    public static float maxHealthScale(float maxHealth, float exponent) {
        if (!(maxHealth > 0f)) return 0f;
        return (float) Math.pow(maxHealth / VANILLA_MAX_HEALTH, exponent);
    }

    /**
     * Health per second the curve restores.
     *
     * <pre>
     * base x (maxHealth/20)^exponent x hungerFactor x (1 + bonus x saturation/cap) x attribute
     * </pre>
     *
     * @param attribute the player's {@code florafare:natural_regeneration}
     * @return 0 or more; 0 below {@code minHunger}
     */
    public static float healPerSecond(Curve curve, float maxHealth, int foodLevel, float saturation,
                                      double attribute) {
        float hunger = hungerFactor(foodLevel, curve.minHunger(), curve.hungerCurve());
        if (hunger <= 0f || !(attribute > 0.0)) return 0f;
        float rate = curve.base()
                * maxHealthScale(maxHealth, curve.maxHealthExponent())
                * hunger
                * saturationFactor(saturation, curve.saturationBonus(), curve.maxSaturation())
                * (float) attribute;
        return rate > 0f ? rate : 0f;
    }

    /** What one curve heal restores: {@link #healPerSecond} over {@link #CURVE_INTERVAL_TICKS}. */
    public static float healPerInterval(Curve curve, float maxHealth, int foodLevel, float saturation,
                                        double attribute) {
        return healPerSecond(curve, maxHealth, foodLevel, saturation, attribute)
                * CURVE_INTERVAL_TICKS / TICKS_PER_SECOND;
    }

    /**
     * The exhaustion a curve heal costs:
     * {@code healed x costPerHealth x (20/maxHealth)^exponent}.
     *
     * <p>The last factor is the rate's max-health scaling turned around, so a second of
     * regeneration costs the same exhaustion at any max health: a 100-health player heals
     * {@code 5^0.5} times as many points per second at the default exponent, and each point
     * costs {@code 1/5^0.5} as much. Without it, every one of those extra points would cost
     * what a point costs a 20-health player, and a bigger health bar would mean a hunger
     * bar that empties faster while healing.
     *
     * @param healed health actually restored; nothing healed costs nothing
     */
    public static float exhaustionCost(float healed, float costPerHealth, float maxHealth, float exponent) {
        if (!(healed > 0f) || !(costPerHealth > 0f) || !(maxHealth > 0f)) return 0f;
        return healed * costPerHealth * (float) Math.pow(VANILLA_MAX_HEALTH / maxHealth, exponent);
    }

    /**
     * {@code exhaustionMultiplier} applied to an exhaustion amount. Only additions are
     * scaled: a negative amount is another mod taking exhaustion away, and multiplying
     * that by 0 would quietly break it.
     */
    public static float scaleExhaustion(float exhaustion, float multiplier) {
        if (!(exhaustion > 0f)) return exhaustion;
        return exhaustion * multiplier;
    }
}
