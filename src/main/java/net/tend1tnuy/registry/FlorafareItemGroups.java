package net.tend1tnuy.registry;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;


public class FlorafareItemGroups {

    public static final ItemGroup FLORAFARE_GROUP = Registry.register(
            Registries.ITEM_GROUP,
            Identifier.of(Florafare.MOD_ID, "florafare_group"),
            FabricItemGroup.builder()
                    .displayName(Text.translatable("itemgroup.florafare.florafare_group"))
                    .icon(() -> new ItemStack(ItemRegistry.FOOD_JOURNAL))
                    .entries((displayContext, entries) -> {
                        entries.add(ItemRegistry.FOOD_JOURNAL);
                        entries.add(ItemRegistry.FORGOTTEN_MEAD);

                    })
                    .build()
    );

    public static void registerItemGroups() {
        Florafare.LOGGER.info("Registering Item Groups for " + Florafare.MOD_ID);
    }
}