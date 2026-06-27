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
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;

import java.util.List;

public class FlorafareHud implements HudRenderCallback {

    private static final float HUD_SCALE = 0.8f;
    private static final int MAX_SLOTS = 3;

    private static class SlotState {
        String currentId = "";
        float animationOffset = -150f;
        ActiveFoodBuff lastBuff = null;
    }

    private final SlotState[] slots = new SlotState[]{new SlotState(), new SlotState(), new SlotState()};

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // Отримуємо компонент гравця, активні бафи та активні синергії
        PlayerFoodComponent foodComponent = ((IFoodComponentProvider) client.player).florafare$getFoodComponent();
        List<ActiveFoodBuff> buffs = foodComponent.getActiveBuffs();
        List<ActiveFoodBuff> activeSynergies = foodComponent.getActiveSynergies();

        boolean isCompact = HudConfig.layoutMode == HudConfig.LayoutMode.COMPACT;

        int slotWidth = isCompact ? 24 : 120;
        int slotHeight = 24;
        int slotSpacing = 28;

        int screenWidth = (int) (context.getScaledWindowWidth() / HUD_SCALE);
        int screenHeight = (int) (context.getScaledWindowHeight() / HUD_SCALE);

        int baseX = 10;
        int baseY = 10;

        switch (HudConfig.position) {
            case TOP_LEFT -> {
                baseX = 10;
                baseY = 10;
            }
            case TOP_RIGHT -> {
                baseX = screenWidth - slotWidth - 10;
                baseY = 10;
            }
            case BOTTOM_LEFT -> {
                baseX = 10;
                baseY = screenHeight - (MAX_SLOTS * slotSpacing) - 10;
            }
            case BOTTOM_RIGHT -> {
                baseX = screenWidth - slotWidth - 10;
                baseY = screenHeight - (MAX_SLOTS * slotSpacing) - 10;
            }
        }

        context.getMatrices().push();
        context.getMatrices().scale(HUD_SCALE, HUD_SCALE, 1.0f);

        for (int i = 0; i < MAX_SLOTS; i++) {
            ActiveFoodBuff buff = (i < buffs.size()) ? buffs.get(i) : null;
            SlotState state = slots[i];
            int y = baseY + (i * slotSpacing);

            if (buff != null) {
                String newId = buff.getConsumedItemId();
                if (!state.currentId.equals(newId)) {
                    state.animationOffset = -slotWidth * 1.5f;
                    state.currentId = newId;
                }
                state.lastBuff = buff;
                state.animationOffset = MathHelper.lerp(0.15f, state.animationOffset, 0f);

                int renderX = calculateAnimatedX(baseX, state.animationOffset);

                // Перевіряємо, чи входить цей баф до активної синергії
                boolean isSynergized = isBuffSynergized(buff, activeSynergies);

                renderSlot(context, client, buff, renderX, y, 1.0f, slotWidth, slotHeight, isCompact, isSynergized);
            } else {
                state.currentId = "";
                if (state.lastBuff != null && Math.abs(state.animationOffset) < slotWidth * 1.2f) {
                    state.animationOffset = MathHelper.lerp(0.15f, state.animationOffset, -slotWidth * 1.5f);
                    float fadeAlpha = Math.max(0.0f, 1.0f - Math.abs(state.animationOffset) / (slotWidth * 1.5f));
                    int renderX = calculateAnimatedX(baseX, state.animationOffset);

                    // Перевіряємо синергію для бафа, що зникає
                    boolean isSynergized = isBuffSynergized(state.lastBuff, activeSynergies);

                    renderSlot(context, client, state.lastBuff, renderX, y, fadeAlpha, slotWidth, slotHeight, isCompact, isSynergized);
                } else {
                    state.lastBuff = null;
                    renderEmptySlot(context, client, baseX, y, slotWidth, slotHeight, isCompact);
                }
            }
        }

        context.getMatrices().pop();
    }

    private int calculateAnimatedX(int baseX, float offset) {
        if (HudConfig.position == HudConfig.ScreenPosition.TOP_RIGHT || HudConfig.position == HudConfig.ScreenPosition.BOTTOM_RIGHT) {
            return baseX - (int) offset;
        }
        return baseX + (int) offset;
    }

    /**
     * Метод для перевірки, чи бере поточний баф участь у будь-якій активній синергії.
     */
    private boolean isBuffSynergized(ActiveFoodBuff buff, List<ActiveFoodBuff> activeSynergies) {
        if (buff == null || activeSynergies == null || activeSynergies.isEmpty()) return false;

        for (ActiveFoodBuff synBuff : activeSynergies) {
            FoodSynergyData data = FoodSynergyManager.getSynergy(synBuff.getTarget());
            // Перевіряємо, чи ID поточного бафу (напр. item:minecraft:apple) є у списку вимог синергії
            if (data != null && data.requirements().contains(buff.getTarget())) {
                return true;
            }
        }
        return false;
    }

    private void renderSlot(DrawContext context, MinecraftClient client, ActiveFoodBuff buff, int x, int y, float fadeAlpha, int slotWidth, int slotHeight, boolean isCompact, boolean isSynergized) {
        float initialDuration = Math.max(1.0f, (float) buff.getInitialDuration());
        int remainingTicks = buff.getDurationRemaining();
        float progress = MathHelper.clamp(remainingTicks / initialDuration, 0.0f, 1.0f);

        boolean isBlinking = progress <= 0.1f || remainingTicks <= 200;
        float blinkAlpha = isBlinking ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 100.0) : 1.0f;
        float finalAlpha = blinkAlpha * fadeAlpha;

        int alphaInt = (int) (finalAlpha * 255);
        if (alphaInt <= 5) return;

        // Фон малюється рівно всередині рамки (+1 піксель відступ від країв)
        context.fill(x + 1, y + 1, x + slotWidth - 1, y + slotHeight - 1, ((int) (finalAlpha * 150) << 24) | 0x000000);

        // Відмальовка рамки залежно від того, чи активна синергія
        if (isSynergized) {
            // Ефект світіння (додаткова напівпрозора рамка навколо слота)
            context.drawBorder(x - 1, y - 1, slotWidth + 2, slotHeight + 2, ((int)(finalAlpha * 100) << 24) | 0xFF8800);
            // Яскрава оранжева внутрішня рамка
            context.drawBorder(x, y, slotWidth, slotHeight, (alphaInt << 24) | 0xFFAA00);
        } else {
            // Стандартна сіра рамка, якщо синергії немає
            context.drawBorder(x, y, slotWidth, slotHeight, (alphaInt << 24) | 0x555555);
        }

        // Смужка прогресу (вписується строго в рамку)
        int barWidth = (int) ((slotWidth - 2) * progress);
        if (barWidth > 0) {
            int colorTop = (isBlinking ? 0xFFFF5555 : 0xFF55FF55) & 0x00FFFFFF | (alphaInt << 24);
            int colorBottom = (isBlinking ? 0xFFAA0000 : 0xFF00AA00) & 0x00FFFFFF | (alphaInt << 24);

            context.fill(x + 1, y + slotHeight - 3, x + 1 + barWidth, y + slotHeight - 2, colorTop);
            context.fill(x + 1, y + slotHeight - 2, x + 1 + barWidth, y + slotHeight - 1, colorBottom);
        }

        // Рендеринг іконки предмета
        ItemStack stack = buff.getConsumedItemStack();
        context.getMatrices().push();
        if (HudConfig.iconSize == HudConfig.IconSize.SMALL) {
            float scale = 0.75f;
            int offsetXY = (int) ((slotHeight - (16 * scale)) / 2);
            context.getMatrices().translate(x + offsetXY, y + offsetXY - 1, 0);
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawItem(stack, 0, 0);
        } else {
            int iconX = x + 4;
            int iconY = y + 2;
            context.drawItem(stack, iconX, iconY);
        }
        context.getMatrices().pop();

        // Відмальовка тексту (тільки для розширеного/стандартного режиму)
        if (!isCompact) {
            int seconds = remainingTicks / 20;
            String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);

            int textY = y + 8;

            context.drawTextWithShadow(client.textRenderer, timeStr, x + 24, textY, (alphaInt << 24) | 0xFFFFFF);

            String name = stack.getName().getString();
            if (client.textRenderer.getWidth(name) > 60) {
                name = client.textRenderer.trimToWidth(name, 55) + "...";
            }
            context.drawTextWithShadow(client.textRenderer, name, x + 58, textY, (alphaInt << 24) | 0xAAAAAA);
        }
    }

    private void renderEmptySlot(DrawContext context, MinecraftClient client, int x, int y, int slotWidth, int slotHeight, boolean isCompact) {
        context.fill(x + 1, y + 1, x + slotWidth - 1, y + slotHeight - 1, 0x33000000);
        context.drawBorder(x, y, slotWidth, slotHeight, 0x44555555);
        if (!isCompact) {
            int textY = y + 8;
            context.drawTextWithShadow(client.textRenderer, Text.translatable("gui.florafare.hud.empty"), x + 24, textY, 0x44FFFFFF);
        }
    }
}