package net.tend1tnuy.florafare.command;

import net.tend1tnuy.florafare.component.ActiveFoodBuff;

/**
 * The ranges every {@code /florafare} command accepts for a buff's numbers, in one place.
 *
 * <p>{@code setbuff} and {@code edit} used to disagree: {@code setbuff} took any duration
 * from 1 up (no ceiling, so a typo'd extra digit was stored and then clamped somewhere
 * else) and any nutrition from 0 up, while {@code edit} capped duration at
 * {@link ActiveFoodBuff#MAX_DURATION_TICKS} and nutrition at 0-20. Neither accepted the
 * two values the runtime has since learned to treat specially. Both commands now read
 * their argument bounds from here:
 *
 * <ul>
 *   <li><b>duration</b> {@code 0..}{@link ActiveFoodBuff#MAX_DURATION_TICKS}: 0 is a
 *       hunger-only entry — nutrition and effects, no buff, no slot — the same meaning a
 *       datapack's {@code duration <= 0} has.</li>
 *   <li><b>nutrition</b> {@code -20..20}: negative values drain hunger, which packs use
 *       for spoiled or cursed food on purpose. 20 is a full bar either way.</li>
 *   <li><b>saturation</b> (the modifier, not points) {@code -5..5}: vanilla's foods sit
 *       between 0.1 and 1.2, and the points a bite restores are nutrition x modifier x 2,
 *       so 5 is already far past a full bar; negative drains like nutrition.</li>
 *   <li><b>health</b> {@code -1024..1024}: the range of {@code generic.max_health}
 *       itself. A negative bonus is a legitimate trade-off food; anything past the
 *       attribute's own bounds is clamped by the game anyway and only misleads.</li>
 * </ul>
 */
public final class CommandBounds {

    private CommandBounds() {}

    public static final int    MIN_DURATION   = 0;
    public static final int    MAX_DURATION   = ActiveFoodBuff.MAX_DURATION_TICKS;
    public static final int    MIN_NUTRITION  = -20;
    public static final int    MAX_NUTRITION  = 20;
    public static final double MIN_SATURATION = -5.0;
    public static final double MAX_SATURATION = 5.0;
    public static final double MIN_HEALTH     = -1024.0;
    public static final double MAX_HEALTH     = 1024.0;

    /** An effect line still has to last at least a tick to be an effect at all. */
    public static final int MIN_EFFECT_TICKS = 1;
    public static final int MAX_AMPLIFIER    = 255;

    /** Whether a set of buff numbers is inside every range above; for tests and API callers. */
    public static boolean inBounds(int duration, int nutrition, double saturation, double health) {
        return duration >= MIN_DURATION && duration <= MAX_DURATION
                && nutrition >= MIN_NUTRITION && nutrition <= MAX_NUTRITION
                && saturation >= MIN_SATURATION && saturation <= MAX_SATURATION
                && health >= MIN_HEALTH && health <= MAX_HEALTH;
    }
}
