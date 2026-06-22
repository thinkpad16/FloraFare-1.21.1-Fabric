package net.tend1tnuy.florafare.client.toast;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.registry.ItemRegistry;

public class FoodDiscoveryToast implements Toast {

    // Correct identifier for toast background sprite in newer versions
    private static final Identifier BACKGROUND_SPRITE = Identifier.of("minecraft", "toast/recipe");
    private final ItemStack icon = new ItemStack(ItemRegistry.FOOD_JOURNAL);

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        // 1. Draw the correct vanilla background using drawGuiTexture
        context.drawGuiTexture(BACKGROUND_SPRITE, 0, 0, this.getWidth(), this.getHeight());

        // 2. Draw Title (Color: Dark Orange/Gold)
        context.drawText(manager.getClient().textRenderer, Text.literal("Culinary Journal"), 30, 7, 0xAA6600, false);

        // 3. Draw Description (Color: Black)
        context.drawText(manager.getClient().textRenderer, Text.literal("New dish discovered!"), 30, 18, 0x000000, false);

        // 4. Draw journal icon
        context.drawItem(icon, 8, 8);

        // Show toast for 5 seconds (5000 milliseconds)
        return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;
    }
}