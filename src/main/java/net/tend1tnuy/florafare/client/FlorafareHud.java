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

public class FlorafareHud implements HudRenderCallback {

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PlayerFoodComponent comp = ((IFoodComponentProvider) client.player).florafare$getFoodComponent();
        List<ActiveFoodBuff> buffs = comp.getActiveBuffs();

        context.getMatrices().push();
        context.getMatrices().scale(0.8f, 0.8f, 1.0f);

        int baseX = 10;
        int baseY = 10;

        // Тепер ми завжди малюємо 3 слоти
        for (int i = 0; i < 3; i++) {
            int y = baseY + (i * 24);

            // 1. Фон слота (малюємо ЗАВЖДИ, але прозорішим, якщо слот порожній)
            boolean hasBuff = i < buffs.size();
            int bgColor = hasBuff ? 0x66000000 : 0x33000000;
            context.fill(baseX, y, baseX + 120, y + 20, bgColor);

            if (hasBuff) {
                ActiveFoodBuff buff = buffs.get(i);
                ItemStack stack = buff.getConsumedItemStack();

                // 2. Прогрес бар таймера
                float progress = (float) buff.getDurationRemaining() / buff.getInitialDuration();
                int barWidth = (int) (120 * progress);
                int barColor = progress > 0.2f ? 0xFF00FF00 : 0xFFFF0000;
                context.fill(baseX, y + 18, baseX + barWidth, y + 20, barColor);

                // 3. Іконка їжі
                context.drawItem(stack, baseX + 2, y + 1);

                // 4. Таймер
                int seconds = buff.getDurationRemaining() / 20;
                String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
                context.drawTextWithShadow(client.textRenderer, timeStr, baseX + 22, y + 6, 0xFFFFFF);

                // 5. Назва
                String name = stack.getName().getString();
                if (client.textRenderer.getWidth(name) > 65) {
                    name = client.textRenderer.trimToWidth(name, 60) + "...";
                }
                context.drawTextWithShadow(client.textRenderer, name, baseX + 55, y + 6, 0xAAAAAA);
            } else {
                // Малюємо ледь помітний текст "Empty" або іконку порожнього слота, якщо хочеш
                context.drawTextWithShadow(client.textRenderer, "Empty", baseX + 22, y + 6, 0x44FFFFFF);
            }
        }

        context.getMatrices().pop();
    }
}