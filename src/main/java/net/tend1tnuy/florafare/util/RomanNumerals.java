package net.tend1tnuy.florafare.util;

/**
 * Overdrive stages as Roman numerals — "I", "II", "III", "IV" — the one way every surface
 * writes them: the HUD badge, the tooltips, the journal, the commands and the load report.
 *
 * <p>Outside {@code client} for the same reason as {@link DurationFormat}: the commands and
 * the datapack diagnostics name stages too, and run where nothing client-side may load.
 */
public final class RomanNumerals {

    private RomanNumerals() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    private static final int[]    VALUES  = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    /**
     * {@code value} in Roman numerals, for 1 to 3999. Anything outside that range — which no
     * real overdrive reaches; it would take a stage list of thousands — is written in Arabic
     * digits rather than refused, since a label is never worth an exception.
     */
    public static String of(int value) {
        if (value < 1 || value > 3999) return String.valueOf(value);
        StringBuilder out = new StringBuilder();
        int rest = value;
        for (int i = 0; i < VALUES.length; i++) {
            while (rest >= VALUES[i]) {
                out.append(SYMBOLS[i]);
                rest -= VALUES[i];
            }
        }
        return out.toString();
    }
}
