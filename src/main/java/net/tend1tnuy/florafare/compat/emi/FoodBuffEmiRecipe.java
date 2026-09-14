package net.tend1tnuy.florafare.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.client.BuffDescription;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.List;

/**
 * One EMI panel: an edible item and what Florafare does when you eat it.
 *
 * <p>The buff is looked up in {@link #addWidgets} rather than captured in the
 * constructor, for two reasons. It keeps the panel correct across a {@code /reload} or
 * a server config change without EMI rebuilding anything, and it lets the discovery
 * gate apply live — the same food reads "unknown" before the player has eaten it and
 * fills in the moment they do, with no reload in between.
 */
public class FoodBuffEmiRecipe implements EmiRecipe {

    private static final int WIDTH  = 160;
    private static final int LINE_HEIGHT = 10;
    /** Room for the heading rule plus the tallest realistic buff block. */
    private static final int MAX_LINES = 11;

    private final EmiStack input;
    private final Identifier id;

    public FoodBuffEmiRecipe(ItemStack stack) {
        this.input = EmiStack.of(stack);
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        // Leading "/" marks this a SYNTHETIC id: EMI looks every recipe id up in the
        // recipe manager, and one that is missing there and does not start with a slash
        // is reported as a mod bug — a red error panel in the category and a warning per
        // entry in the log, which for this category meant one line per edible item in the
        // game. These recipes are generated from the item registry and have no JSON
        // behind them by design, which is exactly what the convention is for.
        this.id = Florafare.id("/food_buff/" + itemId.getNamespace() + "/" + itemId.getPath());
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return FlorafareEmiPlugin.FOOD_BUFFS;
    }

    @Override
    public Identifier getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        return List.of(input);
    }

    @Override
    public List<EmiStack> getOutputs() {
        // The food is not consumed into another item, so there is no output stack.
        // Listing one would make EMI offer this panel as a way to *craft* the food.
        return List.of();
    }

    @Override
    public int getDisplayWidth() {
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return 8 + MAX_LINES * LINE_HEIGHT;
    }

    /** A buff panel is not a crafting step, so it must not appear in recipe trees. */
    @Override
    public boolean supportsRecipeTree() {
        return false;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        ItemStack stack = input.getItemStack();
        widgets.addSlot(input, 0, 0).drawBack(true);
        widgets.addText(stack.getName().copy().formatted(Formatting.WHITE), 22, 5, 0xFFFFFF, true);

        FoodBuffData data = FoodBuffManager.getConfig(stack);

        int y = 24;
        if (data == null) {
            // Either the item is on the exclusion list or another mod owns it.
            widgets.addText(Text.translatable("emi.florafare.not_managed")
                    .formatted(Formatting.DARK_GRAY), 0, y, 0x555555, false);
            return;
        }

        for (Text line : BuffDescription.foodTooltip(stack, data)) {
            if (y > getDisplayHeight() - LINE_HEIGHT) break;
            widgets.addText(line, 0, y, 0xFFFFFF, false);
            y += LINE_HEIGHT;
        }
    }
}
