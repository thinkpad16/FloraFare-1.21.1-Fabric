package net.tend1tnuy.florafare.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;

import java.util.List;

public class FlorafareHud implements HudRenderCallback {

    private static final float HUD_SCALE = 0.8f;
    private static final int BASE_X = 10, BASE_Y = 10;
    private static final int SLOT_WIDTH = 120, SLOT_HEIGHT = 22, SLOT_SPACING = 26;
    private static final int MAX_SLOTS = 3;

    private static class SlotState {
        String currentId = "";
        float x = -SLOT_WIDTH * 1.5f;
        ActiveFoodBuff lastBuff = null;
    }

    private final SlotState[] slots = new SlotState[]{new SlotState(), new SlotState(), new SlotState()};

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        List<ActiveFoodBuff> buffs = ((IFoodComponentProvider) client.player).florafare$getFoodComponent().getActiveBuffs();

        context.getMatrices().push();
        context.getMatrices().scale(HUD_SCALE, HUD_SCALE, 1.0f);

        for (int i = 0; i < MAX_SLOTS; i++) {
            ActiveFoodBuff buff = (i < buffs.size()) ? buffs.get(i) : null;
            SlotState state = slots[i];

            if (buff != null) {
                String newId = buff.getConsumedItemId();
                if (!state.currentId.equals(newId)) {
                    state.x = -SLOT_WIDTH * 1.5f;
                    state.currentId = newId;
                }
                state.lastBuff = buff;
                state.x = MathHelper.lerp(0.15f, state.x, (float) BASE_X);

                renderActiveSlot(context, client, i, buff, (int) state.x, 1.0f);
            } else {
                state.currentId = "";
                if (state.lastBuff != null && state.x > -SLOT_WIDTH * 1.2f) {
                    state.x = MathHelper.lerp(0.15f, state.x, -SLOT_WIDTH * 1.5f);
                    float fadeAlpha = Math.max(0.0f, 1.0f - Math.abs(BASE_X - state.x) / (SLOT_WIDTH + BASE_X));
                    renderActiveSlot(context, client, i, state.lastBuff, (int) state.x, fadeAlpha);
                } else {
                    state.lastBuff = null;
                    renderEmptySlot(context, client, i);
                }
            }
        }

        context.getMatrices().pop();
    }

    private void renderActiveSlot(DrawContext context, MinecraftClient client, int index, ActiveFoodBuff buff, int currentX, float fadeAlpha) {
        int y = BASE_Y + (index * SLOT_SPACING);

        float initialDuration = Math.max(1.0f, (float) buff.getInitialDuration());
        int remainingTicks = buff.getDurationRemaining();
        float progress = MathHelper.clamp(remainingTicks / initialDuration, 0.0f, 1.0f);

        boolean isBlinking = progress <= 0.1f || remainingTicks <= 200;
        float blinkAlpha = isBlinking ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 100.0) : 1.0f;
        float finalAlpha = blinkAlpha * fadeAlpha;

        int alphaInt = (int) (finalAlpha * 255);
        if (alphaInt <= 5) return;

        context.fill(currentX, y, currentX + SLOT_WIDTH, y + SLOT_HEIGHT, ((int) (finalAlpha * 150) << 24) | 0x000000);
        context.drawBorder(currentX, y, SLOT_WIDTH, SLOT_HEIGHT, (alphaInt << 24) | 0x555555);

        int barWidth = (int) ((SLOT_WIDTH - 2) * progress);
        if (barWidth > 0) {
            int colorTop = (isBlinking ? 0xFFFF5555 : 0xFF55FF55) & 0x00FFFFFF | (alphaInt << 24);
            int colorBottom = (isBlinking ? 0xFFAA0000 : 0xFF00AA00) & 0x00FFFFFF | (alphaInt << 24);

            context.fill(currentX + 1, y + 17, currentX + 1 + barWidth, y + 19, colorTop);
            context.fill(currentX + 1, y + 19, currentX + 1 + barWidth, y + 21, colorBottom);
        }

        ItemStack stack = buff.getConsumedItemStack();
        context.drawItem(stack, currentX + 2, y + 2);

        int seconds = remainingTicks / 20;
        String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
        context.drawTextWithShadow(client.textRenderer, timeStr, currentX + 22, y + 6, (alphaInt << 24) | 0xFFFFFF);

        String name = stack.getName().getString();
        if (client.textRenderer.getWidth(name) > 65) {
            name = client.textRenderer.trimToWidth(name, 60) + "...";
        }
        context.drawTextWithShadow(client.textRenderer, name, currentX + 55, y + 6, (alphaInt << 24) | 0xAAAAAA);
    }

    private void renderEmptySlot(DrawContext context, MinecraftClient client, int index) {
        int y = BASE_Y + (index * SLOT_SPACING);
        context.fill(BASE_X, y, BASE_X + SLOT_WIDTH, y + SLOT_HEIGHT, 0x33000000);
        context.drawBorder(BASE_X, y, SLOT_WIDTH, SLOT_HEIGHT, 0x44555555);
        context.drawTextWithShadow(client.textRenderer, Text.translatable("gui.florafare.hud.empty"), BASE_X + 22, y + 6, 0x44FFFFFF);
    }
}