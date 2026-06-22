package net.tend1tnuy.florafare.api;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.jetbrains.annotations.Nullable;

/**
 * Official API for the Florafare mod.
 * Use these methods to interact with the food buff system.
 */
public final class FlorafareAPI {

    private FlorafareAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Gets the buff configuration for a specific ItemStack.
     *
     * @param stack the item to check.
     * @return the buff data, or null if no buff is configured.
     */
    @Nullable
    public static FoodBuffData getBuffData(ItemStack stack) {
        return FoodBuffManager.getConfig(stack);
    }

    /**
     * Checks if the given ItemStack has a unique buff applied via commands.
     *
     * @param stack the item to check.
     * @return true if the item has a custom command-based buff.
     */
    public static boolean hasCustomCommandBuff(ItemStack stack) {
        NbtComponent customDataComp = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customDataComp != null && customDataComp.copyNbt().contains("FlorafareBuffId");
    }

    /**
     * Applies a buff to the player based on the provided configuration.
     *
     * @param player the player receiving the buff.
     * @param stack  the source item (used for context).
     * @param data   the buff configuration data.
     */
    public static void applyBuffToPlayer(PlayerEntity player, ItemStack stack, FoodBuffData data) {
        if (data != null && !player.getWorld().isClient()) {
            // Retrieve the component via the provider interface
            PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();

            // Execute the buff application logic
            component.tryAddBuff(stack, data);
        }
    }
}