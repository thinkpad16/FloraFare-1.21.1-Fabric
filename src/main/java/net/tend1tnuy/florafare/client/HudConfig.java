package net.tend1tnuy.florafare.client;

import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.config.FlorafareConfig;

public class HudConfig {
    public enum LayoutMode    { STANDARD, COMPACT }
    public enum IconSize      { SMALL, LARGE }
    public enum ScreenPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    // Mirrors of the shared bounds in FlorafareConfig, which is also what clamps a
    // hand-edited config file on load — kept here so HUD code reads them off the HUD
    // class, without the two drifting apart.
    public static final float MIN_SCALE = FlorafareConfig.HUD_SCALE_MIN;
    public static final float MAX_SCALE = FlorafareConfig.HUD_SCALE_MAX;

    public static LayoutMode     layoutMode = LayoutMode.COMPACT;
    public static IconSize       iconSize   = IconSize.LARGE;
    public static ScreenPosition position   = ScreenPosition.BOTTOM_LEFT;
    public static float          scale      = 0.8f;

    /** Free pixel offset from the anchor corner, in the HUD's own scaled space. */
    public static int            offsetX    = 0;
    public static int            offsetY    = 0;

    /** Restores the HUD settings persisted in the mod config file. */
    public static void loadFromConfig() {
        layoutMode = parse(LayoutMode.class,     FlorafareConfig.hudLayout,   LayoutMode.COMPACT);
        iconSize   = parse(IconSize.class,       FlorafareConfig.hudIconSize, IconSize.LARGE);
        position   = parse(ScreenPosition.class, FlorafareConfig.hudPosition, ScreenPosition.BOTTOM_LEFT);
        // Clamped defensively in case a hand-edited config file has an out-of-range value.
        scale      = net.minecraft.util.math.MathHelper.clamp(FlorafareConfig.hudScale, MIN_SCALE, MAX_SCALE);
        offsetX    = FlorafareConfig.hudOffsetX;
        offsetY    = FlorafareConfig.hudOffsetY;
    }

    /** Writes the current HUD settings into the mod config file. */
    public static void saveToConfig() {
        FlorafareConfig.hudLayout   = layoutMode.name();
        FlorafareConfig.hudIconSize = iconSize.name();
        FlorafareConfig.hudPosition = position.name();
        FlorafareConfig.hudScale    = scale;
        FlorafareConfig.hudOffsetX  = offsetX;
        FlorafareConfig.hudOffsetY  = offsetY;
        FlorafareConfig.save();
    }

    /**
     * Parses an enum value by name, falling back to {@code fallback} and logging a
     * warning if the name is unrecognized (#21).
     */
    private static <T extends Enum<T>> T parse(Class<T> type, String name, T fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (Exception e) {
            Florafare.LOGGER.warn(
                    "[Florafare] HUD config: unrecognized value '{}' for {}; "
                            + "falling back to '{}'.",
                    name, type.getSimpleName(), fallback.name());
            return fallback;
        }
    }
}
