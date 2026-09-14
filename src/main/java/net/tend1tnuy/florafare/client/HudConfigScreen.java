package net.tend1tnuy.florafare.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.florafare.config.FlorafareConfig;

/**
 * The quick HUD settings screen, opened with the mod's keybind.
 *
 * <p>Laid out as a centred column of rows plus a footer of Reset/Done, rather than a
 * fixed stack of four buttons that anchored itself off the screen centre — every
 * setting added to it used to push the last one further down the screen.
 */
public class HudConfigScreen extends Screen {

    private final Screen parent;

    /** Vertical distance between two stacked option rows. */
    private static final int ROW_SPACING  = 24;
    private static final int ROW_COUNT    = 5;
    private static final int ROW_WIDTH    = 200;
    private static final int ROW_HEIGHT   = 20;

    public HudConfigScreen() {
        this(null);
    }

    public HudConfigScreen(Screen parent) {
        super(Text.translatable("gui.florafare.hud_config.title"));
        this.parent = parent;
    }

    /** Top of the option column, leaving room for the title above it. */
    private int columnTop() {
        return Math.max(40, this.height / 2 - (ROW_COUNT * ROW_SPACING) / 2 - 10);
    }

    private int rowY(int row) {
        return columnTop() + row * ROW_SPACING;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - ROW_WIDTH / 2;

        addRow(x, 0, enabledLabel(), btn -> {
            FlorafareConfig.enableHud = !FlorafareConfig.enableHud;
            btn.setMessage(enabledLabel());
        });

        addRow(x, 1, layoutLabel(), btn -> {
            HudConfig.layoutMode = HudConfig.layoutMode == HudConfig.LayoutMode.STANDARD
                    ? HudConfig.LayoutMode.COMPACT : HudConfig.LayoutMode.STANDARD;
            btn.setMessage(layoutLabel());
        });

        addRow(x, 2, iconLabel(), btn -> {
            HudConfig.iconSize = HudConfig.iconSize == HudConfig.IconSize.LARGE
                    ? HudConfig.IconSize.SMALL : HudConfig.IconSize.LARGE;
            btn.setMessage(iconLabel());
        });

        this.addDrawableChild(new ScaleSliderWidget(x, rowY(3), ROW_WIDTH, ROW_HEIGHT));

        // Position is no longer a four-way cycle — the placement screen covers the
        // corners and everything between them.
        addRow(x, 4, Text.translatable("gui.florafare.hud_config.move"),
                btn -> this.client.setScreen(new HudPlacementScreen(this)));

        int footerY = Math.min(this.height - 28, rowY(ROW_COUNT) + 10);
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.florafare.button.reset"), btn -> resetToDefaults())
                .dimensions(this.width / 2 - 102, footerY, 100, ROW_HEIGHT).build());
        this.addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.florafare.button.done"), btn -> this.close())
                .dimensions(this.width / 2 + 2, footerY, 100, ROW_HEIGHT).build());
    }

    private void addRow(int x, int row, Text label, ButtonWidget.PressAction action) {
        this.addDrawableChild(ButtonWidget.builder(label, action)
                .dimensions(x, rowY(row), ROW_WIDTH, ROW_HEIGHT).build());
    }

    /** Restores every HUD setting to the mod's shipped defaults, then rebuilds the labels. */
    private void resetToDefaults() {
        FlorafareConfig.enableHud = true;
        HudConfig.layoutMode = HudConfig.LayoutMode.COMPACT;
        HudConfig.iconSize   = HudConfig.IconSize.LARGE;
        HudConfig.position   = HudConfig.ScreenPosition.BOTTOM_LEFT;
        HudConfig.scale      = 0.8f;
        HudConfig.offsetX    = 0;
        HudConfig.offsetY    = 0;
        this.clearAndInit();
    }

    // -------------------------------------------------------------------------
    // LABELS
    // -------------------------------------------------------------------------

    /**
     * Toggle labels share one on/off word list, so a setting reads the same here and in
     * the ModMenu screen.
     */
    private static Text toggleLabel(String key, boolean value) {
        return Text.translatable(key, Text.translatable(
                value ? "gui.florafare.toggle.on" : "gui.florafare.toggle.off"));
    }

    private static Text enabledLabel() {
        return toggleLabel("gui.florafare.hud_config.enabled", FlorafareConfig.enableHud);
    }

    private static Text layoutLabel() {
        return Text.translatable("gui.florafare.hud_config.mode",
                Text.translatable("gui.florafare.layout." + HudConfig.layoutMode.name().toLowerCase()));
    }

    private static Text iconLabel() {
        return Text.translatable("gui.florafare.hud_config.icons",
                Text.translatable("gui.florafare.icon_size." + HudConfig.iconSize.name().toLowerCase()));
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
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, columnTop() - 22, 0xFFFFFF);
        if (!FlorafareConfig.enableHud) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("gui.florafare.hud_config.disabled_note")
                            .formatted(Formatting.DARK_GRAY),
                    this.width / 2, columnTop() - 11, 0x888888);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        // Persist the HUD settings so they survive game restarts.
        HudConfig.saveToConfig();
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void removed() {
        // Covers the paths that bypass close(), e.g. the player hitting Escape.
        HudConfig.saveToConfig();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
