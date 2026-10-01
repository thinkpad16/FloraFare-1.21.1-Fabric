package net.tend1tnuy.florafare.util;

import java.util.Locale;

/**
 * Tick counts as clock times, the one way every surface writes them.
 *
 * <p>Lives outside {@code client} on purpose: the commands print durations too, and they
 * run on a dedicated server where nothing under {@code client} may be loaded. The command
 * used to keep its own copy of the formatter for exactly that reason, and the two copies
 * had already started to drift.
 *
 * <p>Three shapes:
 * <ul>
 *   <li>{@code mm:ss} below an hour — {@code 05:00}, the form the overlay and the tooltip
 *       have always used, so nothing a player already knows changes;</li>
 *   <li>{@code h:mm:ss} from an hour up — a buff may now last up to 24 hours
 *       ({@code ActiveFoodBuff.MAX_DURATION_TICKS}), and "1440:00" for a day reads as a
 *       typo rather than a time;</li>
 *   <li>{@code ∞} for vanilla's infinite effect duration ({@code -1}), which is what a
 *       datapack writes to ask for one. Vanilla's own potion tooltip prints the same
 *       glyph, so the font is known to have it.</li>
 * </ul>
 *
 * <p>Formatted with {@link Locale#ROOT}: {@code %02d} under some default locales (Arabic,
 * Persian, Hindi with native digits) produces digits the game font has no glyphs for.
 */
public final class DurationFormat {

    private DurationFormat() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Vanilla's {@code StatusEffectInstance.INFINITE}, spelled out so this stays loadable anywhere. */
    public static final int INFINITE_TICKS = -1;

    /** What an infinite duration reads as. */
    public static final String INFINITE = "∞";

    private static final int TICKS_PER_SECOND = 20;
    private static final int SECONDS_PER_HOUR = 3600;

    /**
     * {@code mm:ss}, {@code h:mm:ss} or {@code ∞}. A partial second is truncated, never
     * rounded up — the overlay counts down, and showing a second that has already begun
     * to run out would make the last one last twice. Any other negative count (an
     * already-expired buff) reads as zero rather than as a negative time.
     */
    public static String format(int ticks) {
        if (ticks == INFINITE_TICKS) return INFINITE;
        int seconds = Math.max(0, ticks) / TICKS_PER_SECOND;
        int hours = seconds / SECONDS_PER_HOUR;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d",
                    hours, (seconds / 60) % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
