package net.tend1tnuy.florafare.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;

/**
 * Drag the buff overlay where you want it.
 *
 * <p>Four fixed corners are not enough in practice: every HUD mod wants a corner, and
 * the player is the only one who knows which one is free on their screen. This draws a
 * to-scale preview of the overlay, drags it with the mouse, and stores the result as an
 * anchor corner plus an offset (see {@link HudLayout}).
 *
 * <p>The preview is a mock rather than the live overlay, because the live one is empty
 * most of the time — and an empty overlay is exactly what the player cannot aim.
 */
public class HudPlacementScreen extends Screen {

    private final Screen parent;

    private boolean dragging = false;
    /** Grab point inside the panel, so it does not jump to the cursor on click. */
    private int grabOffsetX = 0;
    private int grabOffsetY = 0;

    // The settings as they were on open, for Reset/Cancel.
    private final HudConfig.ScreenPosition originalPosition;
    private final int originalOffsetX;
    private final int originalOffsetY;

    public HudPlacementScreen(Screen parent) {
        super(Text.translatable("gui.florafare.hud_placement.title"));
        this.parent = parent;
        this.originalPosition = HudConfig.position;
        this.originalOffsetX  = HudConfig.offsetX;
        this.originalOffsetY  = HudConfig.offsetY;
    }

    @Override
    protected void init() {
        int y = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.florafare.button.reset"), btn -> {
                    HudConfig.offsetX = 0;
                    HudConfig.offsetY = 0;
                    HudConfig.position = HudConfig.ScreenPosition.BOTTOM_LEFT;
                }).dimensions(this.width / 2 - 154, y, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.florafare.button.cancel"), btn -> {
                    HudConfig.position = originalPosition;
                    HudConfig.offsetX  = originalOffsetX;
                    HudConfig.offsetY  = originalOffsetY;
                    this.close();
                }).dimensions(this.width / 2 - 50, y, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.florafare.button.done"), btn -> this.close())
                .dimensions(this.width / 2 + 54, y, 100, 20).build());
    }

    // -------------------------------------------------------------------------
    // GEOMETRY
    // -------------------------------------------------------------------------

    private int slotCount() {
        return PlayerFoodComponent.getMaxBuffSlots();
    }

    private boolean isCompact() {
        return HudConfig.layoutMode == HudConfig.LayoutMode.COMPACT;
    }

    private int scaledWidth() {
        return HudLayout.scaledWidth(this.width, HudConfig.scale);
    }

    private int scaledHeight() {
        return HudLayout.scaledHeight(this.height, HudConfig.scale);
    }

    /** Panel bounds in real screen pixels, for hit-testing against the mouse. */
    private int panelScreenX() {
        int x = HudLayout.baseX(HudConfig.position, scaledWidth(),
                HudLayout.slotWidth(isCompact()), HudConfig.offsetX);
        return Math.round(x * HudConfig.scale);
    }

    private int panelScreenY() {
        int y = HudLayout.baseY(HudConfig.position, scaledHeight(), slotCount(), HudConfig.offsetY);
        return Math.round(y * HudConfig.scale);
    }

    private int panelScreenWidth() {
        return Math.round(HudLayout.slotWidth(isCompact()) * HudConfig.scale);
    }

    private int panelScreenHeight() {
        return Math.round(HudLayout.panelHeight(slotCount()) * HudConfig.scale);
    }

    // -------------------------------------------------------------------------
    // INPUT
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0
                && mouseX >= panelScreenX() && mouseX < panelScreenX() + panelScreenWidth()
                && mouseY >= panelScreenY() && mouseY < panelScreenY() + panelScreenHeight()) {
            dragging    = true;
            grabOffsetX = (int) mouseX - panelScreenX();
            grabOffsetY = (int) mouseY - panelScreenY();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!dragging) {
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        // Back into the HUD's own scaled space, then store as an offset from the
        // current anchor. Re-anchoring is deferred to mouseReleased so the panel does
        // not jump between corners while the cursor is still moving.
        int targetX = Math.round(((float) mouseX - grabOffsetX) / HudConfig.scale);
        int targetY = Math.round(((float) mouseY - grabOffsetY) / HudConfig.scale);

        HudConfig.offsetX = HudLayout.offsetXFor(HudConfig.position, scaledWidth(),
                HudLayout.slotWidth(isCompact()), targetX);
        HudConfig.offsetY = HudLayout.offsetYFor(HudConfig.position, scaledHeight(),
                slotCount(), targetY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            reanchorToNearestCorner();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Re-expresses the finished position relative to whichever corner it now sits
     * nearest. The panel does not move — the same pixels, measured from a closer edge —
     * but it now keeps that placement when the window is resized.
     */
    private void reanchorToNearestCorner() {
        int slotWidth = HudLayout.slotWidth(isCompact());
        int sw = scaledWidth();
        int sh = scaledHeight();

        int x = HudLayout.baseX(HudConfig.position, sw, slotWidth, HudConfig.offsetX);
        int y = HudLayout.baseY(HudConfig.position, sh, slotCount(), HudConfig.offsetY);

        HudConfig.ScreenPosition anchor = HudLayout.nearestAnchor(
                x + slotWidth / 2, y + HudLayout.panelHeight(slotCount()) / 2, sw, sh);

        HudConfig.position = anchor;
        HudConfig.offsetX  = HudLayout.offsetXFor(anchor, sw, slotWidth, x);
        HudConfig.offsetY  = HudLayout.offsetYFor(anchor, sh, slotCount(), y);
    }

    // -------------------------------------------------------------------------
    // RENDERING
    // -------------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        drawQuadrantGuides(context);

        context.getMatrices().push();
        context.getMatrices().scale(HudConfig.scale, HudConfig.scale, 1.0f);
        drawPreviewPanel(context);
        context.getMatrices().pop();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 16, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("gui.florafare.hud_placement.hint").formatted(Formatting.GRAY),
                this.width / 2, 30, 0xAAAAAA);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("gui.florafare.hud_placement.anchor",
                        Text.translatable("gui.florafare.position." + HudConfig.position.name().toLowerCase()))
                        .formatted(Formatting.DARK_GRAY),
                this.width / 2, 42, 0x888888);

        super.render(context, mouseX, mouseY, delta);
    }

    /** Faint cross showing which quadrant the panel will anchor to when released. */
    private void drawQuadrantGuides(DrawContext context) {
        int midX = this.width / 2;
        int midY = this.height / 2;
        context.fill(midX, 0, midX + 1, this.height, 0x22FFFFFF);
        context.fill(0, midY, this.width, midY + 1, 0x22FFFFFF);
    }

    /** A to-scale mock of the overlay: one filled slot, the rest empty outlines. */
    private void drawPreviewPanel(DrawContext context) {
        int slotWidth = HudLayout.slotWidth(isCompact());
        int x = HudLayout.baseX(HudConfig.position, scaledWidth(), slotWidth, HudConfig.offsetX);
        int y = HudLayout.baseY(HudConfig.position, scaledHeight(), slotCount(), HudConfig.offsetY);

        for (int i = 0; i < slotCount(); i++) {
            int slotY = y + i * HudLayout.SLOT_SPACING;

            context.fill(x + 1, slotY + 1, x + slotWidth - 1, slotY + HudLayout.SLOT_HEIGHT - 1,
                    dragging ? 0xAA201808 : 0x96000000);
            context.drawBorder(x, slotY, slotWidth, HudLayout.SLOT_HEIGHT,
                    dragging ? 0xFFFFAA00 : 0xFF555555);

            if (i == 0) {
                // A single sample slot, so scale and layout read at a glance.
                ItemStack sample = new ItemStack(Items.GOLDEN_CARROT);
                if (HudConfig.iconSize == HudConfig.IconSize.SMALL) {
                    context.getMatrices().push();
                    float scale = 0.75f;
                    int inset = (int) ((HudLayout.SLOT_HEIGHT - (16 * scale)) / 2);
                    context.getMatrices().translate(x + inset, slotY + inset - 1, 0);
                    context.getMatrices().scale(scale, scale, 1.0f);
                    context.drawItem(sample, 0, 0);
                    context.getMatrices().pop();
                } else {
                    context.drawItem(sample, x + 4, slotY + 2);
                }

                context.fill(x + 1, slotY + HudLayout.SLOT_HEIGHT - 3,
                        x + 1 + (slotWidth - 2) * 2 / 3, slotY + HudLayout.SLOT_HEIGHT - 1,
                        0xFF55FF55);

                if (!isCompact()) {
                    context.drawTextWithShadow(this.textRenderer, "02:30",
                            x + 24, slotY + 8, 0xFFFFFFFF);
                }
            }
        }
    }

    @Override
    public void close() {
        HudConfig.saveToConfig();
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
