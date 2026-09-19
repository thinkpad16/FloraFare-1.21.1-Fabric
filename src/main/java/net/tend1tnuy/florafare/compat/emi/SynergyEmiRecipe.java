package net.tend1tnuy.florafare.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.client.BuffDescription;
import net.tend1tnuy.florafare.food.FoodSynergyData;

import java.util.ArrayList;
import java.util.List;

/**
 * One EMI panel per synergy: the dishes that have to be active at once, and what the
 * combination grants.
 *
 * <p>Synergies are meant to be found, not looked up, so an undiscovered one shows a
 * locked panel — no name, no requirements, no effects. The lock is enforced twice over:
 * {@link #addWidgets} redraws it live, so a synergy unlocks the instant the player
 * triggers it, and {@link #getInputs} withholds the requirement stacks while it is
 * locked, which keeps the ingredients out of EMI's search index. Without that second
 * half, typing an item name into EMI would list the secret combinations it belongs to.
 *
 * <p><b>Sizing.</b> As in {@link FoodBuffEmiRecipe}, the panel is measured from the text
 * it has to hold rather than fixed at 160 pixels, and the text is wrapped to the panel
 * on the way in — a long effect line used to run off the right-hand edge. The
 * requirement slots wrap onto further rows for the same reason.
 */
public class SynergyEmiRecipe implements EmiRecipe {

    private static final int MIN_WIDTH   = 160;
    private static final int MAX_WIDTH   = 220;
    private static final int LINE_HEIGHT = 10;
    private static final int SLOT_SIZE   = 18;
    /** Slots per row, leaving room beside the first row for the synergy's name. */
    private static final int SLOTS_PER_ROW = 6;
    /** Row budget used when there is no text renderer to measure with. */
    private static final int FALLBACK_LINES = 9;

    private final FoodSynergyData synergy;
    private final Identifier id;
    private final List<EmiIngredient> requirements;

    /** Measured on first use and cached; see {@link #measure}. */
    private int width = -1;
    private int height = -1;

    public SynergyEmiRecipe(FoodSynergyData synergy) {
        this.synergy = synergy;
        // Synthetic id — see FoodBuffEmiRecipe. Synergies come from a datapack of their
        // own, but not from the recipe manager, which is the only place EMI looks.
        this.id = Florafare.id("/synergy/" + synergy.id().replace(':', '/'));
        this.requirements = resolveRequirements(synergy);
    }

    /**
     * Turns each requirement string into an EMI ingredient. Tag requirements stay tags
     * rather than being flattened into a stack list, so EMI cycles through the matching
     * items in the slot the same way the journal does.
     */
    private static List<EmiIngredient> resolveRequirements(FoodSynergyData synergy) {
        List<EmiIngredient> result = new ArrayList<>();
        for (String req : synergy.requirements()) {
            if (req.startsWith("#")) {
                Identifier tagId = Identifier.tryParse(req.substring(1));
                if (tagId != null) {
                    result.add(EmiIngredient.of(TagKey.of(RegistryKeys.ITEM, tagId)));
                }
            } else {
                Identifier itemId = Identifier.tryParse(req);
                if (itemId != null && net.minecraft.registry.Registries.ITEM.containsId(itemId)) {
                    result.add(EmiStack.of(net.minecraft.registry.Registries.ITEM.get(itemId)));
                }
            }
        }
        return result;
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return FlorafareEmiPlugin.SYNERGIES;
    }

    @Override
    public Identifier getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        return BuffDescription.isSynergyDiscovered(synergy) ? requirements : List.of();
    }

    @Override
    public List<EmiStack> getOutputs() {
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

    @Override
    public boolean supportsRecipeTree() {
        return false;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        boolean discovered = BuffDescription.isSynergyDiscovered(synergy);
        int panelWidth = getDisplayWidth();

        // A locked panel shows one empty slot where the requirements would be, so the
        // category still reads as a list of dishes rather than a list of blank rows.
        int nameX = SLOT_SIZE + 4;
        if (discovered) {
            int drawn = 0;
            for (EmiIngredient requirement : requirements) {
                widgets.addSlot(requirement,
                        (drawn % SLOTS_PER_ROW) * SLOT_SIZE,
                        (drawn / SLOTS_PER_ROW) * SLOT_SIZE).drawBack(true);
                drawn++;
            }
            nameX = Math.min(drawn, SLOTS_PER_ROW) * SLOT_SIZE + 4;
        } else {
            widgets.addSlot(EmiStack.EMPTY, 0, 0).drawBack(true);
        }

        Text name = discovered
                ? BuffDescription.synergyName(synergy.id()).copy().formatted(Formatting.GOLD)
                : Text.literal("???").formatted(Formatting.DARK_GRAY);
        addName(widgets, name, nameX, panelWidth, discovered ? 0xFFAA00 : 0x555555);

        List<Text> body = discovered
                ? BuffDescription.synergyLines(synergy)
                : BuffDescription.lockedLines();

        int y = textTop();
        int bottom = getDisplayHeight() - LINE_HEIGHT;
        for (Text line : BuffDescription.wrapToWidth(body, panelWidth)) {
            if (y > bottom) break;
            widgets.addText(line, 0, y, discovered ? 0xFFFFFF : 0x555555, false);
            y += LINE_HEIGHT;
        }
    }

    /** See {@link FoodBuffEmiRecipe#addName} — trimmed keeping its own styling. */
    private void addName(WidgetHolder widgets, Text name, int x, int panelWidth, int color) {
        TextRenderer textRenderer = textRenderer();
        if (textRenderer == null) {
            widgets.addText(name, x, 5, color, false);
            return;
        }
        widgets.addText(Language.getInstance().reorder(textRenderer.trimToWidth(name, panelWidth - x)),
                x, 5, color, false);
    }

    /** First text row: below however many rows of requirement slots there are. */
    private int textTop() {
        int slotRows = Math.max(1,
                (requirements.size() + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW);
        return slotRows * SLOT_SIZE + 8;
    }

    /**
     * Sizes the panel for both states at once — locked and discovered — since
     * {@link #addWidgets} swaps between them the moment the player triggers the synergy
     * and EMI does not re-measure in between.
     */
    private void measure() {
        TextRenderer textRenderer = textRenderer();
        if (textRenderer == null) {
            width = MIN_WIDTH;
            height = textTop() + FALLBACK_LINES * LINE_HEIGHT;
            return;
        }

        // Both placements of the name: beside the one empty slot of a locked panel, and
        // beside the first row of requirement slots once it is discovered.
        int slotsWide = Math.min(requirements.size(), SLOTS_PER_ROW) * SLOT_SIZE;
        int widest = Math.max(
                SLOT_SIZE + 4 + textRenderer.getWidth("???"),
                slotsWide + 4 + textRenderer.getWidth(BuffDescription.synergyName(synergy.id())));

        List<List<Text>> states = List.of(
                BuffDescription.lockedLines(), BuffDescription.synergyLines(synergy));
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
        height = textTop() + rows * LINE_HEIGHT;
    }

    private static TextRenderer textRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? null : client.textRenderer;
    }
}
