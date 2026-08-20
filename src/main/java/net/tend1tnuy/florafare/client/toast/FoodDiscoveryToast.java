package net.tend1tnuy.florafare.client.toast;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.registry.ItemRegistry;

/**
 * Custom toast notification triggered when a player discovers a new food buff
 * or a new synergy. Two factory methods produce the two variants; both share
 * the same layout and entrance animation, differing only in icon/title/text.
 */
public class FoodDiscoveryToast implements Toast {

    private static final Identifier BACKGROUND_SPRITE = Identifier.of("minecraft", "toast/recipe");
    private static final long ENTRANCE_DURATION_MS = 180L;

    // Toast is 160px wide; text starts at x=30, leaving ~6px right margin.
    private static final int TEXT_X = 30;
    private static final int MAX_TEXT_WIDTH = 124;

    private final ItemStack icon;
    private final Text title;
    private final String description;

    private FoodDiscoveryToast(ItemStack icon, Text title, String description) {
        this.icon = icon;
        this.title = title;
        this.description = description;
    }

    /** Toast shown the first time a player eats a food Florafare has a buff for. */
    public static FoodDiscoveryToast forFood(ItemStack discoveredStack) {
        return new FoodDiscoveryToast(
                discoveredStack,
                Text.translatable("toast.florafare.discovery.title"),
                Text.translatable("toast.florafare.discovery.food", discoveredStack.getName()).getString());
    }

    /** Toast shown the first time a synergy's requirements become satisfied. */
    public static FoodDiscoveryToast forSynergy(Text synergyName) {
        return new FoodDiscoveryToast(
                new ItemStack(ItemRegistry.FOOD_JOURNAL),
                Text.translatable("toast.florafare.discovery.synergy_title"),
                Text.translatable("toast.florafare.discovery.synergy", synergyName).getString());
    }

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

        TextRenderer textRenderer = manager.getClient().textRenderer;

        context.drawText(textRenderer, title, TEXT_X, 7, 0xAA6600, false);

        // Discovered names are arbitrary datapack/vanilla text and can easily overflow
        // the toast's fixed width, so trim long ones with an ellipsis rather than
        // letting them spill past the background sprite.
        String shownDescription = description;
        if (textRenderer.getWidth(shownDescription) > MAX_TEXT_WIDTH) {
            shownDescription = textRenderer.trimToWidth(shownDescription, MAX_TEXT_WIDTH - textRenderer.getWidth("...")) + "...";
        }
        context.drawText(textRenderer, shownDescription, TEXT_X, 18, 0x000000, false);

        context.drawItem(icon, 8, 8);

        context.getMatrices().pop();

        return startTime >= net.tend1tnuy.florafare.config.FlorafareConfig.toastDisplayTimeMs
                ? Visibility.HIDE : Visibility.SHOW;
    }
}
