package net.tend1tnuy.florafare.client;

import net.tend1tnuy.florafare.config.FlorafareConfig;

public class HudConfig {
    public enum LayoutMode { STANDARD, COMPACT }
    public enum IconSize { SMALL, LARGE }
    public enum ScreenPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static LayoutMode layoutMode = LayoutMode.COMPACT;
    public static IconSize iconSize = IconSize.LARGE;
    public static ScreenPosition position = ScreenPosition.BOTTOM_LEFT;

    /** Restores the HUD settings persisted in the mod config file. */
    public static void loadFromConfig() {
        layoutMode = parse(LayoutMode.class, FlorafareConfig.hudLayout, LayoutMode.COMPACT);
        iconSize = parse(IconSize.class, FlorafareConfig.hudIconSize, IconSize.LARGE);
        position = parse(ScreenPosition.class, FlorafareConfig.hudPosition, ScreenPosition.BOTTOM_LEFT);
    }

    /** Writes the current HUD settings into the mod config file. */
    public static void saveToConfig() {
        FlorafareConfig.hudLayout = layoutMode.name();
        FlorafareConfig.hudIconSize = iconSize.name();
        FlorafareConfig.hudPosition = position.name();
        FlorafareConfig.save();
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String name, T fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (Exception e) {
            return fallback;
        }
    }
}
