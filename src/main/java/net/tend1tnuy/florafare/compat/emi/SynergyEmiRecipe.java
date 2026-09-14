package net.tend1tnuy.florafare.compat.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
 */
public class SynergyEmiRecipe implements EmiRecipe {

    private static final int WIDTH       = 160;
    private static final int LINE_HEIGHT = 10;
    private static final int SLOT_SIZE   = 18;
    private static final int MAX_LINES   = 9;

    private final FoodSynergyData synergy;
    private final Identifier id;
    private final List<EmiIngredient> requirements;

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
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return SLOT_SIZE + 8 + MAX_LINES * LINE_HEIGHT;
    }

    @Override
    public boolean supportsRecipeTree() {
        return false;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        boolean discovered = BuffDescription.isSynergyDiscovered(synergy);

        if (!discovered) {
            widgets.addSlot(EmiStack.EMPTY, 0, 0).drawBack(true);
            widgets.addText(Text.literal("???").formatted(Formatting.DARK_GRAY),
                    SLOT_SIZE + 4, 5, 0x555555, false);
            int y = SLOT_SIZE + 8;
            for (Text line : BuffDescription.lockedLines()) {
                widgets.addText(line, 0, y, 0x555555, false);
                y += LINE_HEIGHT;
            }
            return;
        }

        int x = 0;
        for (EmiIngredient requirement : requirements) {
            widgets.addSlot(requirement, x, 0).drawBack(true);
            x += SLOT_SIZE;
        }

        widgets.addText(BuffDescription.synergyName(synergy.id()).copy()
                        .formatted(Formatting.GOLD),
                x + 4, 5, 0xFFAA00, false);

        int y = SLOT_SIZE + 8;
        for (Text line : BuffDescription.synergyLines(synergy)) {
            if (y > getDisplayHeight() - LINE_HEIGHT) break;
            widgets.addText(line, 0, y, 0xFFFFFF, false);
            y += LINE_HEIGHT;
        }
    }
}
