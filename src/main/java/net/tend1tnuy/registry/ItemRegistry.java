package net.tend1tnuy.registry;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.item.ForgottenMeadItem;

public class ItemRegistry {

    //public static final Item TEST_ITEM = registerItem("test_item", new Item(new Item.Settings()));

    public static final Item FORGOTTEN_MEAD = registerItem("forgotten_mead", new ForgottenMeadItem(new Item.Settings()
            .maxCount(16)
            .food(new FoodComponent.Builder().nutrition(0).saturationModifier(0f).alwaysEdible().build())));

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(Florafare.MOD_ID, name), item);
    }

    public static void initialize() {
        Florafare.LOGGER.info("Registering items for " + Florafare.MOD_ID);
    }
}