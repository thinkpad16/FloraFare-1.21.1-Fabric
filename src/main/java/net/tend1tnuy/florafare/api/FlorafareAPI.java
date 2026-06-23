package net.tend1tnuy.florafare.api;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.jetbrains.annotations.Nullable;

/**
 * Official API for the Florafare mod.
 * Provides safe methods to interact with the food buff system.
 */
public final class FlorafareAPI {

    private FlorafareAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Retrieves the buff configuration for a specific ItemStack.
     *
     * @param stack The item to check.
     * @return The buff data, or null if no buff is configured.
     */
    @Nullable
    public static FoodBuffData getBuffData(ItemStack stack) {
        return FoodBuffManager.getConfig(stack);
    }

    /**
     * Checks if the given ItemStack has a unique buff applied via commands.
     *
     * @param stack The item to check.
     * @return True if the item contains a custom command-based buff identifier.
     */
    public static boolean hasCustomCommandBuff(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customData != null && customData.copyNbt().contains("FlorafareBuffId");
    }

    /**
     * Applies a food buff to the player based on the provided configuration.
     *
     * @param player The player receiving the buff.
     * @param stack  The consumed item.
     * @param data   The buff configuration data.
     */
    public static void applyBuffToPlayer(PlayerEntity player, ItemStack stack, FoodBuffData data) {
        if (data != null && !player.getWorld().isClient()) {
            ((IFoodComponentProvider) player).florafare$getFoodComponent().tryAddBuff(stack, data);
        }
    }
}