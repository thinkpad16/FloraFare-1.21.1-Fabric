package net.tend1tnuy.florafare.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tells {@code PlayerEntity#canConsume} which stack it is being asked about.
 *
 * <p>{@code Item#use} knows the hand being used and asks canConsume whether the player
 * may eat, but canConsume takes only a boolean — so Florafare's "edible when full" check
 * in {@link PlayerEntityMixin} had to inspect both hands and grant the exemption if
 * either qualified. A managed food in one hand thereby made an ignored food in the other
 * edible on a full bar. Recording the used hand's stack for the length of that one call
 * lets the check look at exactly the food being eaten.
 *
 * <p>A {@code @WrapOperation} so it composes with any other mod wrapping the same call,
 * and the record is cleared in a {@code finally} so an exception thrown further down the
 * chain cannot leave it pointing at a stale stack.
 */
@Mixin(Item.class)
public abstract class ItemUseMixin {

    @WrapOperation(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;canConsume(Z)Z"
            )
    )
    private boolean florafare$recordUsedStack(PlayerEntity player, boolean ignoreHunger,
                                              Operation<Boolean> original,
                                              World world, PlayerEntity user, Hand hand) {
        PlayerFoodComponent component =
                ((IFoodComponentProvider) player).florafare$getFoodComponent();
        component.setConsumeCheckStack(user.getStackInHand(hand));
        try {
            return original.call(player, ignoreHunger);
        } finally {
            component.setConsumeCheckStack(null);
        }
    }
}
