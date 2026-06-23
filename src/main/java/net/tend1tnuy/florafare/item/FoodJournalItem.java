package net.tend1tnuy.florafare.item;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.tend1tnuy.florafare.network.OpenFoodJournalPayload;

/**
 * Item representation of the Food Journal.
 * Opens the custom GUI when used by the player.
 */
public class FoodJournalItem extends Item {

    public FoodJournalItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new OpenFoodJournalPayload());
        }

        return TypedActionResult.success(stack);
    }
}