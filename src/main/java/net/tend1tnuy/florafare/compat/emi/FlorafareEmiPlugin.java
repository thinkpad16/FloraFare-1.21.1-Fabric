package net.tend1tnuy.florafare.compat.emi;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.registry.ItemRegistry;

/**
 * EMI integration: a "Food Buffs" category listing what every edible item does when
 * eaten, and a "Synergies" category listing the hidden combinations.
 *
 * <p><b>Why the buff category is built from the item registry rather than from the
 * loaded configs.</b> Florafare's configs reach the client in a packet sent on join,
 * while EMI rebuilds its recipe list off the vanilla recipe-sync packet that arrives
 * earlier in the same login. Building the list from the config map would therefore race
 * the sync and, more often than not, produce an empty category. The item registry is
 * fully populated long before either, so a recipe is created per edible item and the
 * buff itself is resolved at draw time. That also means the panel follows a
 * {@code /reload} or a config change with no rebuild at all.
 *
 * <p>Synergies have no registry to enumerate, so those recipes do come from the synced
 * map — and {@link EmiReloadBridge} asks EMI to rebuild once that sync lands.
 */
public class FlorafareEmiPlugin implements EmiPlugin {

    public static final EmiRecipeCategory FOOD_BUFFS = new EmiRecipeCategory(
            Florafare.id("food_buffs"),
            EmiStack.of(ItemRegistry.FOOD_JOURNAL),
            EmiStack.of(ItemRegistry.FOOD_JOURNAL));

    public static final EmiRecipeCategory SYNERGIES = new EmiRecipeCategory(
            Florafare.id("synergies"),
            EmiStack.of(net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE),
            EmiStack.of(net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE));

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(FOOD_BUFFS);

        // The journal is the in-game equivalent of this panel, so EMI offers it as the
        // "workstation" for both categories — clicking it opens the whole list.
        registry.addWorkstation(FOOD_BUFFS, EmiStack.of(ItemRegistry.FOOD_JOURNAL));

        int foods = 0;
        for (Item item : Registries.ITEM) {
            ItemStack stack = item.getDefaultStack();
            if (!stack.contains(DataComponentTypes.FOOD)) continue;
            // Florafare's own mead is a buff remover, not a buff source.
            if (item == ItemRegistry.FORGOTTEN_MEAD) continue;

            registry.addRecipe(new FoodBuffEmiRecipe(stack));
            foods++;
        }

        int synergies = 0;
        if (FlorafareConfig.enableSynergies) {
            registry.addCategory(SYNERGIES);
            registry.addWorkstation(SYNERGIES, EmiStack.of(ItemRegistry.FOOD_JOURNAL));

            for (FoodSynergyData synergy : FoodSynergyManager.getAllSynergies()) {
                registry.addRecipe(new SynergyEmiRecipe(synergy));
                synergies++;
            }
        }

        Florafare.LOGGER.info("EMI integration registered {} food entries and {} synergies.",
                foods, synergies);
    }
}
