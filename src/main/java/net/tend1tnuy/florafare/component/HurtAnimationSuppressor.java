package net.tend1tnuy.florafare.component;

import net.minecraft.util.Util;

/**
 * Client-side one-shot flag armed by {@code SuppressHurtAnimationPayload}.
 * The next health-decrease update within the window skips the vanilla hurt
 * animation; the time limit keeps a stale flag from ever eating a real hit.
 */
public final class HurtAnimationSuppressor {

    private static final long WINDOW_MS = 1000;

    private static volatile long armedUntilMs = 0;

    private HurtAnimationSuppressor() {}

    public static void arm() {
        armedUntilMs = Util.getMeasuringTimeMs() + WINDOW_MS;
    }

    /** Returns true (and disarms) if a suppression was armed recently. */
    public static boolean tryConsume() {
        if (Util.getMeasuringTimeMs() <= armedUntilMs) {
            armedUntilMs = 0;
            return true;
        }
        return false;
    }
}
