package net.tend1tnuy.florafare.food;

/**
 * The saturation ceiling applied when a player eats (see {@code HungerManagerMixin}).
 */
public final class SaturationCap {

    private SaturationCap() {}

    /**
     * @param configured     {@code maxSaturation}: 0 or less means vanilla
     * @param vanillaCeiling vanilla's ceiling for this bite, the player's hunger level
     *                       after eating
     * @return the most saturation the player may hold after this bite
     */
    public static float ceiling(float configured, float vanillaCeiling) {
        return configured > 0f ? configured : vanillaCeiling;
    }
}
