package net.tend1tnuy.florafare.client.toast;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.registry.ItemRegistry;

/**
 * Toast notification displayed when the player discovers a new food item.
 */
public class FoodDiscoveryToast implements Toast {

    private static final Identifier BACKGROUND_SPRITE =
            Identifier.of("minecraft", "toast/recipe");

    private static final Text TITLE =
            Text.literal("Culinary Journal");

    private static final Text DESCRIPTION =
            Text.literal("A new dish has been discovered!");

    private static final int TITLE_COLOR = 0xAA6600;
    private static final int DESCRIPTION_COLOR = 0x000000;
    private static final long DISPLAY_TIME = 5000L;

    private final ItemStack icon = new ItemStack(ItemRegistry.FOOD_JOURNAL);

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        // Draw vanilla toast background
        context.drawGuiTexture(
                BACKGROUND_SPRITE,
                0,
                0,
                getWidth(),
                getHeight()
        );

        // Draw title and description
        context.drawText(
                manager.getClient().textRenderer,
                TITLE,
                30,
                7,
                TITLE_COLOR,
                false
        );

        context.drawText(
                manager.getClient().textRenderer,
                DESCRIPTION,
                30,
                18,
                DESCRIPTION_COLOR,
                false
        );

        // Draw journal icon
        context.drawItem(icon, 8, 8);

        return startTime >= DISPLAY_TIME
                ? Visibility.HIDE
                : Visibility.SHOW;
    }
}