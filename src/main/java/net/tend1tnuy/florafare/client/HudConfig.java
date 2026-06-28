package net.tend1tnuy.florafare.client;

public class HudConfig {
    public enum LayoutMode { STANDARD, COMPACT }
    public enum IconSize { SMALL, LARGE }
    public enum ScreenPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static LayoutMode layoutMode = LayoutMode.COMPACT;
    public static IconSize iconSize = IconSize.LARGE;
    public static ScreenPosition position = ScreenPosition.BOTTOM_LEFT;
}