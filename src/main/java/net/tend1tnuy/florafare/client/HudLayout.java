package net.tend1tnuy.florafare.client;

import net.minecraft.util.math.MathHelper;

/**
 * Where the buff overlay sits on screen, in one place.
 *
 * <p>Two things draw the overlay's box — {@link FlorafareHud} for real, and
 * {@link HudPlacementScreen} as a draggable preview — and a placement screen that
 * disagrees with the thing it is placing is worse than no placement screen at all. Both
 * call the same four methods here.
 *
 * <p>The position is an anchor corner plus a free pixel offset. The corner alone (which
 * is all there used to be) cannot avoid another mod's overlay, and a free offset alone
 * behaves badly when the window is resized; together, the panel keeps its relationship
 * to its corner at any window size and still goes exactly where the player puts it.
 *
 * <p>All coordinates are in the overlay's own scaled space — that is, after
 * {@code HudConfig.scale} has been applied — which is the space the HUD's draw calls
 * use.
 */
public final class HudLayout {

    private HudLayout() {}

    public static final int SLOT_HEIGHT  = 24;
    public static final int SLOT_SPACING = 28;
    /** Gap kept between the panel and the screen edge at zero offset. */
    public static final int MARGIN       = 10;

    public static int slotWidth(boolean compact) {
        return compact ? 24 : 120;
    }

    /** Full height of the slot column, including the gap below the last slot. */
    public static int panelHeight(int slotCount) {
        return Math.max(1, slotCount) * SLOT_SPACING;
    }

    /** Width of the overlay's scaled coordinate space for the current window. */
    public static int scaledWidth(int windowWidth, float hudScale) {
        return (int) (windowWidth / hudScale);
    }

    public static int scaledHeight(int windowHeight, float hudScale) {
        return (int) (windowHeight / hudScale);
    }

    /**
     * Left edge of the panel. The offset is applied on top of the anchor and the result
     * is clamped to the screen, so a stale offset from a larger window (or a hand-edited
     * config) can never push the overlay out of view.
     */
    public static int baseX(HudConfig.ScreenPosition position, int screenWidth,
                            int slotWidth, int offsetX) {
        int anchored = switch (position) {
            case TOP_LEFT, BOTTOM_LEFT   -> MARGIN;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - slotWidth - MARGIN;
        };
        return MathHelper.clamp(anchored + offsetX, 0, Math.max(0, screenWidth - slotWidth));
    }

    /** Top edge of the panel, clamped the same way as {@link #baseX}. */
    public static int baseY(HudConfig.ScreenPosition position, int screenHeight,
                            int slotCount, int offsetY) {
        int height = panelHeight(slotCount);
        int anchored = switch (position) {
            case TOP_LEFT, TOP_RIGHT       -> MARGIN;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - height - MARGIN;
        };
        return MathHelper.clamp(anchored + offsetY, 0, Math.max(0, screenHeight - height));
    }

    /**
     * The corner a panel at this position belongs to, by which screen quadrant its own
     * centre falls in. Used when a drag ends: re-anchoring to the nearest corner is what
     * makes the placement survive a window resize, instead of the panel drifting because
     * it is still measured from the far edge.
     */
    public static HudConfig.ScreenPosition nearestAnchor(int panelCenterX, int panelCenterY,
                                                         int screenWidth, int screenHeight) {
        boolean left = panelCenterX < screenWidth / 2;
        boolean top  = panelCenterY < screenHeight / 2;
        if (top)  return left ? HudConfig.ScreenPosition.TOP_LEFT    : HudConfig.ScreenPosition.TOP_RIGHT;
        return       left ? HudConfig.ScreenPosition.BOTTOM_LEFT : HudConfig.ScreenPosition.BOTTOM_RIGHT;
    }

    /**
     * The offset that puts a panel's top-left corner at {@code (x, y)} for the given
     * anchor — the inverse of {@link #baseX}/{@link #baseY}, used to convert a finished
     * drag back into stored settings.
     */
    public static int offsetXFor(HudConfig.ScreenPosition position, int screenWidth,
                                 int slotWidth, int x) {
        int anchored = switch (position) {
            case TOP_LEFT, BOTTOM_LEFT   -> MARGIN;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - slotWidth - MARGIN;
        };
        return x - anchored;
    }

    public static int offsetYFor(HudConfig.ScreenPosition position, int screenHeight,
                                 int slotCount, int y) {
        int anchored = switch (position) {
            case TOP_LEFT, TOP_RIGHT       -> MARGIN;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - panelHeight(slotCount) - MARGIN;
        };
        return y - anchored;
    }
}
