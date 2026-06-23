package net.tend1tnuy.florafare.client.toast;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.registry.ItemRegistry;

/**
 * Custom toast notification triggered when a player discovers a new food buff.
 */
public class FoodDiscoveryToast implements Toast {

    private static final Identifier BACKGROUND_SPRITE = Identifier.of("minecraft", "toast/recipe");
    private static final long DISPLAY_TIME_MS = 5000L;

    private final ItemStack icon = new ItemStack(ItemRegistry.FOOD_JOURNAL);

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        context.drawGuiTexture(BACKGROUND_SPRITE, 0, 0, this.getWidth(), this.getHeight());

        context.drawText(manager.getClient().textRenderer,
                Text.translatable("toast.florafare.discovery.title"), 30, 7, 0xAA6600, false);

        context.drawText(manager.getClient().textRenderer,
                Text.translatable("toast.florafare.discovery.description"), 30, 18, 0x000000, false);

        context.drawItem(icon, 8, 8);

        return startTime >= DISPLAY_TIME_MS ? Visibility.HIDE : Visibility.SHOW;
    }
}