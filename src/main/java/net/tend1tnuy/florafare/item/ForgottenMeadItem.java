package net.tend1tnuy.florafare.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;

/**
 * A special consumable item that clears the most recently added food buff
 * from the player's active effects and returns an empty glass bottle.
 */
public class ForgottenMeadItem extends Item {

    public ForgottenMeadItem(Settings settings) {
        super(settings);
    }

    /**
     * Refuses to start drinking while the item is switched off.
     *
     * <p>{@code enableForgottenMead} only ever gated the buff removal, so with it off the
     * bottle was still drunk, still consumed, and still returned a glass bottle — it just
     * quietly did nothing, which reads as the item being broken rather than disabled. The
     * flag is server-authoritative and synced, so client and server refuse in step.
     */
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!FlorafareConfig.enableForgottenMead) {
            if (!world.isClient) {
                user.sendMessage(Text.translatable("message.florafare.mead_disabled")
                        .formatted(Formatting.RED), true);
            }
            return TypedActionResult.fail(stack);
        }
        return super.use(world, user, hand);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        ItemStack result = super.finishUsing(stack, world, user);

        if (!world.isClient && user instanceof ServerPlayerEntity player
                && FlorafareConfig.enableForgottenMead) {
            PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();
            component.removeLastBuff();
        }

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