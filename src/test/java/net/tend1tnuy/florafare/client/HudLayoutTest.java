package net.tend1tnuy.florafare.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay's position is an anchor corner plus a free offset, and two things compute
 * it: the HUD that draws it and the placement screen that drags it. If those two ever
 * disagree the panel jumps the moment the player lets go of the mouse, so the geometry
 * is pinned down here.
 */
class HudLayoutTest {

    private static final int SCREEN_W = 640;
    private static final int SCREEN_H = 360;
    private static final int SLOT_W   = 24;
    private static final int SLOTS    = 3;

    @Nested
    @DisplayName("anchoring")
    class Anchoring {

        @Test
        @DisplayName("a zero offset sits one margin in from its corner")
        void zeroOffsetHugsTheCorner() {
            assertEquals(HudLayout.MARGIN,
                    HudLayout.baseX(HudConfig.ScreenPosition.TOP_LEFT, SCREEN_W, SLOT_W, 0));
            assertEquals(HudLayout.MARGIN,
                    HudLayout.baseY(HudConfig.ScreenPosition.TOP_LEFT, SCREEN_H, SLOTS, 0));

            assertEquals(SCREEN_W - SLOT_W - HudLayout.MARGIN,
                    HudLayout.baseX(HudConfig.ScreenPosition.BOTTOM_RIGHT, SCREEN_W, SLOT_W, 0));
            assertEquals(SCREEN_H - HudLayout.panelHeight(SLOTS) - HudLayout.MARGIN,
                    HudLayout.baseY(HudConfig.ScreenPosition.BOTTOM_RIGHT, SCREEN_H, SLOTS, 0));
        }

        @Test
        @DisplayName("an offset moves the panel right and down from its anchor")
        void offsetShiftsFromAnchor() {
            assertEquals(HudLayout.MARGIN + 40,
                    HudLayout.baseX(HudConfig.ScreenPosition.TOP_LEFT, SCREEN_W, SLOT_W, 40));
            assertEquals(HudLayout.MARGIN + 25,
                    HudLayout.baseY(HudConfig.ScreenPosition.TOP_LEFT, SCREEN_H, SLOTS, 25));
        }
    }

    @Nested
    @DisplayName("staying on screen")
    class Clamping {

        @Test
        @DisplayName("an offset that would push the panel off the right edge is clamped")
        void clampsToRightEdge() {
            int x = HudLayout.baseX(HudConfig.ScreenPosition.TOP_LEFT, SCREEN_W, SLOT_W, 10_000);
            assertEquals(SCREEN_W - SLOT_W, x);
        }

        @Test
        @DisplayName("a negative offset cannot push the panel off the left edge")
        void clampsToLeftEdge() {
            assertEquals(0,
                    HudLayout.baseX(HudConfig.ScreenPosition.TOP_LEFT, SCREEN_W, SLOT_W, -10_000));
            assertEquals(0,
                    HudLayout.baseY(HudConfig.ScreenPosition.TOP_LEFT, SCREEN_H, SLOTS, -10_000));
        }

        @Test
        @DisplayName("a stale offset from a bigger window still lands on a small one")
        void survivesShrinkingTheWindow() {
            // Placed near the right edge of a wide window, then the window is halved.
            int offset = 900;
            int x = HudLayout.baseX(HudConfig.ScreenPosition.TOP_LEFT, 320, SLOT_W, offset);
            assertTrue(x >= 0 && x + SLOT_W <= 320,
                    "the panel must remain fully on screen, was at x=" + x);
        }

        @Test
        @DisplayName("a window narrower than the panel still yields a drawable position")
        void windowNarrowerThanPanel() {
            int x = HudLayout.baseX(HudConfig.ScreenPosition.TOP_LEFT, 10, 120, 0);
            assertEquals(0, x, "clamping must not produce a negative coordinate");
        }
    }

    @Nested
    @DisplayName("drag round-trip")
    class DragRoundTrip {

        /**
         * What the placement screen does on release: convert a pixel position into an
         * offset, then draw from that offset. The panel must not move.
         */
        @Test
        @DisplayName("converting a position to an offset and back is lossless")
        void offsetIsTheInverseOfBase() {
            for (HudConfig.ScreenPosition anchor : HudConfig.ScreenPosition.values()) {
                for (int target : new int[]{0, 37, 200, SCREEN_W - SLOT_W}) {
                    int offset = HudLayout.offsetXFor(anchor, SCREEN_W, SLOT_W, target);
                    assertEquals(target,
                            HudLayout.baseX(anchor, SCREEN_W, SLOT_W, offset),
                            "x round-trip failed for " + anchor + " at " + target);
                }
                for (int target : new int[]{0, 51, SCREEN_H - HudLayout.panelHeight(SLOTS)}) {
                    int offset = HudLayout.offsetYFor(anchor, SCREEN_H, SLOTS, target);
                    assertEquals(target,
                            HudLayout.baseY(anchor, SCREEN_H, SLOTS, offset),
                            "y round-trip failed for " + anchor + " at " + target);
                }
            }
        }

        @Test
        @DisplayName("re-anchoring to the nearest corner leaves the panel where it was")
        void reanchoringDoesNotMoveThePanel() {
            // Dragged to the bottom-right quadrant while still anchored top-left. The
            // position has to be one the panel can actually occupy — y = 300 would put
            // its bottom edge at 384 on a 360-tall screen, and the clamp would (rightly)
            // pull it back, which is a different case; see clampsToRightEdge.
            HudConfig.ScreenPosition from = HudConfig.ScreenPosition.TOP_LEFT;
            int x = 500;
            int y = 250;

            HudConfig.ScreenPosition to = HudLayout.nearestAnchor(
                    x + SLOT_W / 2, y + HudLayout.panelHeight(SLOTS) / 2, SCREEN_W, SCREEN_H);
            assertEquals(HudConfig.ScreenPosition.BOTTOM_RIGHT, to);

            int newOffsetX = HudLayout.offsetXFor(to, SCREEN_W, SLOT_W, x);
            int newOffsetY = HudLayout.offsetYFor(to, SCREEN_H, SLOTS, y);

            assertEquals(x, HudLayout.baseX(to, SCREEN_W, SLOT_W, newOffsetX));
            assertEquals(y, HudLayout.baseY(to, SCREEN_H, SLOTS, newOffsetY));
            assertEquals(from, HudConfig.ScreenPosition.TOP_LEFT, "sanity: the source anchor is unchanged");
        }

        /**
         * Dragging past an edge is clamped on the way in, and re-anchoring normalizes
         * the stored offset to the position actually occupied — so the next window
         * resize starts from a sane number rather than the runaway one the drag left.
         */
        @Test
        @DisplayName("a drag past the edge settles at the edge, with a normalized offset")
        void dragPastTheEdgeNormalizes() {
            HudConfig.ScreenPosition anchor = HudConfig.ScreenPosition.TOP_LEFT;
            int drawnY = HudLayout.baseY(anchor, SCREEN_H, SLOTS, 10_000);
            assertEquals(SCREEN_H - HudLayout.panelHeight(SLOTS), drawnY);

            int normalized = HudLayout.offsetYFor(anchor, SCREEN_H, SLOTS, drawnY);
            assertEquals(drawnY, HudLayout.baseY(anchor, SCREEN_H, SLOTS, normalized));
            assertTrue(normalized < 10_000, "the runaway offset must not be kept as-is");
        }
    }

    @Nested
    @DisplayName("nearest corner")
    class NearestCorner {

        @Test
        @DisplayName("each quadrant picks its own corner")
        void picksByQuadrant() {
            assertEquals(HudConfig.ScreenPosition.TOP_LEFT,
                    HudLayout.nearestAnchor(10, 10, SCREEN_W, SCREEN_H));
            assertEquals(HudConfig.ScreenPosition.TOP_RIGHT,
                    HudLayout.nearestAnchor(SCREEN_W - 10, 10, SCREEN_W, SCREEN_H));
            assertEquals(HudConfig.ScreenPosition.BOTTOM_LEFT,
                    HudLayout.nearestAnchor(10, SCREEN_H - 10, SCREEN_W, SCREEN_H));
            assertEquals(HudConfig.ScreenPosition.BOTTOM_RIGHT,
                    HudLayout.nearestAnchor(SCREEN_W - 10, SCREEN_H - 10, SCREEN_W, SCREEN_H));
        }
    }
}
