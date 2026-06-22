package net.tend1tnuy.registry;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.item.FoodJournalItem;
import net.tend1tnuy.florafare.item.ForgottenMeadItem;

/**
 * Registry for all items added by the Florafare mod.
 */
public class ItemRegistry {

    public static final Item FORGOTTEN_MEAD = registerItem("forgotten_mead", new ForgottenMeadItem(new Item.Settings()
            .maxCount(16)
            .food(new FoodComponent.Builder().nutrition(0).saturationModifier(0f).alwaysEdible().build())));
    public static final Item FOOD_JOURNAL = registerItem("food_journal", new FoodJournalItem(new Item.Settings()
            .maxCount(1)));
    /**
     * Registers an item to the Minecraft item registry.
     *
     * @param name The unique name of the item.
     * @param item The item instance to register.
     * @return The registered item.
     */
    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(Florafare.MOD_ID, name), item);
    }

    /**
     * Initializes the item registry.
     */
    public static void initialize() {
        Florafare.LOGGER.info("Registering mod items for " + Florafare.MOD_ID);
    }
}