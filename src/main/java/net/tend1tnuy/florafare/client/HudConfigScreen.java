package net.tend1tnuy.florafare.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

public class HudConfigScreen extends Screen {

    public HudConfigScreen() {
        super(Text.translatable("gui.florafare.hud_config.title"));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = this.height / 2 - 40;

        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.florafare.hud_config.mode", HudConfig.layoutMode.name()),
                btn -> {
                    HudConfig.layoutMode = HudConfig.layoutMode == HudConfig.LayoutMode.STANDARD ? HudConfig.LayoutMode.COMPACT : HudConfig.LayoutMode.STANDARD;
                    btn.setMessage(Text.translatable("gui.florafare.hud_config.mode", HudConfig.layoutMode.name()));
                }).dimensions(x, y, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.florafare.hud_config.icons", HudConfig.iconSize.name()),
                btn -> {
                    HudConfig.iconSize = HudConfig.iconSize == HudConfig.IconSize.LARGE ? HudConfig.IconSize.SMALL : HudConfig.IconSize.LARGE;
                    btn.setMessage(Text.translatable("gui.florafare.hud_config.icons", HudConfig.iconSize.name()));
                }).dimensions(x, y + 25, 200, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.florafare.hud_config.position", HudConfig.position.name()),
                btn -> {
                    int nextOrd = (HudConfig.position.ordinal() + 1) % HudConfig.ScreenPosition.values().length;
                    HudConfig.position = HudConfig.ScreenPosition.values()[nextOrd];
                    btn.setMessage(Text.translatable("gui.florafare.hud_config.position", HudConfig.position.name()));
                }).dimensions(x, y + 50, 200, 20).build());

        this.addDrawableChild(new ScaleSliderWidget(x, y + 75, 200, 20));
    }

    /**
     * Lets the player resize the whole HUD overlay. The slider itself is dragged
     * continuously, but the applied/displayed value is snapped to 5% steps so it
     * always reads as a clean percentage instead of an odd fractional one.
     */
    private static class ScaleSliderWidget extends SliderWidget {
        private static final float STEP = 0.05f;

        ScaleSliderWidget(int x, int y, int width, int height) {
            super(x, y, width, height, Text.empty(), normalize(HudConfig.scale));
            this.updateMessage();
        }

        private static double normalize(float scale) {
            return (scale - HudConfig.MIN_SCALE) / (HudConfig.MAX_SCALE - HudConfig.MIN_SCALE);
        }

        private float snappedScale() {
            float raw = HudConfig.MIN_SCALE + (float) (this.value * (HudConfig.MAX_SCALE - HudConfig.MIN_SCALE));
            return MathHelper.clamp(Math.round(raw / STEP) * STEP, HudConfig.MIN_SCALE, HudConfig.MAX_SCALE);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.translatable("gui.florafare.hud_config.scale",
                    Math.round(snappedScale() * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            HudConfig.scale = snappedScale();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
        // Persist the HUD settings so they survive game restarts
        HudConfig.saveToConfig();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}