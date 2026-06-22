package net.tend1tnuy.florafare.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;

import java.util.List;

/**
 * Handles the rendering of the active food buff overlay on the HUD.
 */
public class FlorafareHud implements HudRenderCallback {

    private static final float HUD_SCALE = 0.8f;
    private static final int BASE_X = 10;
    private static final int BASE_Y = 10;
    private static final int SLOT_WIDTH = 120;
    private static final int SLOT_HEIGHT = 20;
    private static final int SLOT_SPACING = 24;
    private static final int MAX_SLOTS = 3;

    // Colors
    private static final int BG_COLOR_ACTIVE = 0x66000000;
    private static final int BG_COLOR_EMPTY = 0x33000000;
    private static final int BAR_COLOR_HEALTHY = 0xFF00FF00;
    private static final int BAR_COLOR_CRITICAL = 0xFFFF0000;
    private static final int TEXT_COLOR_WHITE = 0xFFFFFF;
    private static final int TEXT_COLOR_GRAY = 0xAAAAAA;
    private static final int TEXT_COLOR_EMPTY = 0x44FFFFFF;

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PlayerFoodComponent component = ((IFoodComponentProvider) client.player).florafare$getFoodComponent();
        List<ActiveFoodBuff> buffs = component.getActiveBuffs();

        context.getMatrices().push();
        context.getMatrices().scale(HUD_SCALE, HUD_SCALE, 1.0f);

        for (int i = 0; i < MAX_SLOTS; i++) {
            renderSlot(context, client, i, i < buffs.size() ? buffs.get(i) : null);
        }

        context.getMatrices().pop();
    }

    private void renderSlot(DrawContext context, MinecraftClient client, int index, ActiveFoodBuff buff) {
        int y = BASE_Y + (index * SLOT_SPACING);

        // 1. Draw Slot Background
        boolean hasBuff = buff != null;
        int bgColor = hasBuff ? BG_COLOR_ACTIVE : BG_COLOR_EMPTY;
        context.fill(BASE_X, y, BASE_X + SLOT_WIDTH, y + SLOT_HEIGHT, bgColor);

        if (hasBuff) {
            ItemStack stack = buff.getConsumedItemStack();

            // 2. Draw Progress Bar
            float initialDuration = Math.max(1.0f, (float) buff.getInitialDuration());
            float progress = Math.min(1.0f, Math.max(0.0f, buff.getDurationRemaining() / initialDuration));

            int barWidth = (int) (SLOT_WIDTH * progress);
            int barColor = progress > 0.2f ? BAR_COLOR_HEALTHY : BAR_COLOR_CRITICAL;
            context.fill(BASE_X, y + 18, BASE_X + barWidth, y + 20, barColor);

            // 3. Draw Item Icon
            context.drawItem(stack, BASE_X + 2, y + 1);

            // 4. Draw Timer Text
            int seconds = buff.getDurationRemaining() / 20;
            String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
            context.drawTextWithShadow(client.textRenderer, timeStr, BASE_X + 22, y + 6, TEXT_COLOR_WHITE);

            // 5. Draw Buff Name
            String name = stack.getName().getString();
            if (client.textRenderer.getWidth(name) > 65) {
                name = client.textRenderer.trimToWidth(name, 60) + "...";
            }
            context.drawTextWithShadow(client.textRenderer, name, BASE_X + 55, y + 6, TEXT_COLOR_GRAY);
        } else {
            // Draw Empty Slot Placeholder
            context.drawTextWithShadow(client.textRenderer, "Empty", BASE_X + 22, y + 6, TEXT_COLOR_EMPTY);
        }
    }
}