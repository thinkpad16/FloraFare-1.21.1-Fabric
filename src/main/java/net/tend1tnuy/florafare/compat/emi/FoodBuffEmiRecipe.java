package net.tend1tnuy.florafare.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.client.BuffDescription;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.ArrayList;
import java.util.List;

/**
 * One EMI panel: an edible item and what Florafare does when you eat it.
 *
 * <p>The buff is looked up in {@link #addWidgets} rather than captured in the
 * constructor, for two reasons. It keeps the panel correct across a {@code /reload} or
 * a server config change without EMI rebuilding anything, and it lets the discovery
 * gate apply live — the same food reads "unknown" before the player has eaten it and
 * fills in the moment they do, with no reload in between.
 *
 * <p><b>Sizing.</b> The panel used to be a fixed 160×118 box that the text was simply
 * drawn into, so any line wider than 160 pixels — "Restores 8 hunger, 12.8 saturation"
 * in English, and rather more of them in a longer language — ran out past the panel's
 * right-hand edge and over EMI's own chrome. The box is now measured from the text it
 * has to hold, and the text is wrapped to the box on the way in, so neither can escape
 * the other. EMI reads the size once per {@link dev.emi.emi.api.recipe.EmiRecipe} and
 * calls {@code addWidgets} later, which is why {@link #measure} sizes for the locked
 * <em>and</em> the unlocked block: discovery flips between them with no reload.
 */
public class FoodBuffEmiRecipe implements EmiRecipe {

    /** Floor, so a one-line buff still looks like a panel rather than a label. */
    private static final int MIN_WIDTH = 160;
    /** Ceiling, so one wordy modded attribute cannot stretch the whole category. */
    private static final int MAX_WIDTH = 220;
    private static final int LINE_HEIGHT = 10;
    /** Left edge of the item name, clear of the 18-pixel slot. */
    private static final int NAME_X = 22;
    /** First text row, below the slot. */
    private static final int TEXT_Y = 24;
    /** Row budget used when there is no text renderer to measure with. */
    private static final int FALLBACK_LINES = 11;

    private final EmiStack input;
    private final Identifier id;

    /** Measured on first use and cached; see {@link #measure}. */
    private int width = -1;
    private int height = -1;

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
        if (width < 0) measure();
        return width;
    }

    @Override
    public int getDisplayHeight() {
        if (height < 0) measure();
        return height;
    }

    /** A buff panel is not a crafting step, so it must not appear in recipe trees. */
    @Override
    public boolean supportsRecipeTree() {
        return false;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        ItemStack stack = input.getItemStack();
        int panelWidth = getDisplayWidth();

        widgets.addSlot(input, 0, 0).drawBack(true);
        addName(widgets, stack, panelWidth);

        FoodBuffData data = FoodBuffManager.getConfig(stack);
        // Either the item is on the exclusion list or another mod owns it.
        boolean managed = data != null;
        List<Text> body = managed
                ? BuffDescription.foodTooltip(stack, data)
                : List.of(notManagedLine());

        int color = managed ? 0xFFFFFF : 0x555555;
        int bottom = getDisplayHeight() - LINE_HEIGHT;
        int y = TEXT_Y;
        for (Text line : BuffDescription.wrapToWidth(body, panelWidth)) {
            if (y > bottom) break;
            widgets.addText(line, 0, y, color, false);
            y += LINE_HEIGHT;
        }
    }

    /**
     * The item name, trimmed to whatever the panel has left beside the slot. The width
     * was measured to fit it, so this only ever bites for a renamed stack; it is trimmed
     * as an ordered text so the trim keeps the name's own styling.
     */
    private void addName(WidgetHolder widgets, ItemStack stack, int panelWidth) {
        Text name = stack.getName().copy().formatted(Formatting.WHITE);
        TextRenderer textRenderer = textRenderer();
        if (textRenderer == null) {
            widgets.addText(name, NAME_X, 5, 0xFFFFFF, true);
            return;
        }
        widgets.addText(Language.getInstance().reorder(
                        textRenderer.trimToWidth(name, panelWidth - NAME_X)),
                NAME_X, 5, 0xFFFFFF, true);
    }

    /**
     * Sizes the panel to the widest line and the tallest block it could ever draw,
     * wrapping included — measured once, since EMI asks for the size ahead of the
     * widgets and keeps it for the life of the display.
     */
    private void measure() {
        ItemStack stack = input.getItemStack();
        TextRenderer textRenderer = textRenderer();
        List<List<Text>> states = states(stack);

        if (textRenderer == null) {
            width = MIN_WIDTH;
            height = TEXT_Y + FALLBACK_LINES * LINE_HEIGHT;
            return;
        }

        int widest = NAME_X + textRenderer.getWidth(stack.getName());
        for (List<Text> state : states) {
            for (Text line : state) {
                widest = Math.max(widest, textRenderer.getWidth(line));
            }
        }
        width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widest));

        int rows = 1;
        for (List<Text> state : states) {
            rows = Math.max(rows, BuffDescription.wrapToWidth(state, width).size());
        }
        height = TEXT_Y + rows * LINE_HEIGHT;
    }

    /**
     * Every block this panel could ever draw: the locked placeholder and the full
     * breakdown both, because {@link #addWidgets} switches between them the instant the
     * player eats the dish and nothing re-measures in between.
     */
    private static List<List<Text>> states(ItemStack stack) {
        FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data == null) return List.of(List.of(notManagedLine()));

        List<Text> locked = new ArrayList<>();
        locked.add(BuffDescription.header());
        locked.addAll(BuffDescription.lockedLines());

        List<Text> unlocked = new ArrayList<>();
        unlocked.add(BuffDescription.header());
        unlocked.addAll(BuffDescription.foodBuffLines(data));

        return List.of(locked, unlocked);
    }

    private static Text notManagedLine() {
        return Text.translatable("emi.florafare.not_managed").formatted(Formatting.DARK_GRAY);
    }

    private static TextRenderer textRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? null : client.textRenderer;
    }
}
