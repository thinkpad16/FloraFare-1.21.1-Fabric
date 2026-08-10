package net.tend1tnuy.florafare.client.toast;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.registry.ItemRegistry;

/**
 * Custom toast notification triggered when a player discovers a new food buff.
 */
public class FoodDiscoveryToast implements Toast {

    private static final Identifier BACKGROUND_SPRITE = Identifier.of("minecraft", "toast/recipe");
    private static final long ENTRANCE_DURATION_MS = 180L;

    private final ItemStack icon = new ItemStack(ItemRegistry.FOOD_JOURNAL);

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        // Entrance pop: slides in from the left and grows to full size, eased out
        // (fast start, gentle settle) — the exit slide is already handled by the
        // vanilla ToastManager once this returns HIDE.
        float t     = MathHelper.clamp(startTime / (float) ENTRANCE_DURATION_MS, 0.0f, 1.0f);
        float eased = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        float slideX = (1.0f - eased) * -16.0f;
        float scale  = 0.85f + 0.15f * eased;

        float pivotX = this.getWidth()  / 2.0f;
        float pivotY = this.getHeight() / 2.0f;

        context.getMatrices().push();
        context.getMatrices().translate(pivotX + slideX, pivotY, 0);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.getMatrices().translate(-pivotX, -pivotY, 0);

        context.drawGuiTexture(BACKGROUND_SPRITE, 0, 0, this.getWidth(), this.getHeight());

        context.drawText(manager.getClient().textRenderer,
                Text.translatable("toast.florafare.discovery.title"), 30, 7, 0xAA6600, false);

        context.drawText(manager.getClient().textRenderer,
                Text.translatable("toast.florafare.discovery.description"), 30, 18, 0x000000, false);

        context.drawItem(icon, 8, 8);

        context.getMatrices().pop();

        return startTime >= net.tend1tnuy.florafare.config.FlorafareConfig.toastDisplayTimeMs
                ? Visibility.HIDE : Visibility.SHOW;
    }
}