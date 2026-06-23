package net.tend1tnuy.florafare.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;

/**
 * A special consumable item that clears the most recently added food buff
 * from the player's active effects and returns an empty glass bottle.
 */
public class ForgottenMeadItem extends Item {

    public ForgottenMeadItem(Settings settings) {
        super(settings);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        // Handle base food consumption logic (e.g., decrementing stack size)
        ItemStack result = super.finishUsing(stack, world, user);

        // Remove the last active buff on the server side
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();
            component.removeLastBuff();
        }

        // Return a glass bottle to the player if not in creative mode
        if (user instanceof PlayerEntity player && !player.getAbilities().creativeMode) {
            ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
            if (result.isEmpty()) {
                return bottle;
            } else if (!player.getInventory().insertStack(bottle)) {
                player.dropItem(bottle, false);
            }
        }

        return result;
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.DRINK;
    }
}