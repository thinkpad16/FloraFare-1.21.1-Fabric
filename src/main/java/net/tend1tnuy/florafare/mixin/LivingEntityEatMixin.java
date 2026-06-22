package net.tend1tnuy.florafare.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.stat.Stats;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link LivingEntity} to intercept the food consumption process
 * and inject Florafare's custom buff logic.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEatMixin {

    /**
     * Intercepts the eatFood method to apply custom buffs and cancel vanilla food behavior.
     */
    @Inject(method = "eatFood", at = @At("HEAD"), cancellable = true)
    private void onEatFood(World world, ItemStack stack, FoodComponent food, CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClient && (Object) this instanceof ServerPlayerEntity player) {

            FoodBuffData data = FoodBuffManager.getConfig(stack);

            if (data != null) {
                PlayerFoodComponent comp = ((IFoodComponentProvider) player).florafare$getFoodComponent();

                // === НОВЕ: Відкриваємо їжу в книзі ===
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                comp.unlockFood(itemId);

                // 1. Always apply base nutrition from the datapack, even if slots are full
                player.getHungerManager().add(data.nutrition(), data.saturation());

                // 2. Attempt to add the buff to the player's buff slots
                comp.tryAddBuff(stack, data);

                // 3. Replicate vanilla consumption behavior (play sounds, decrement item)
                player.incrementStat(Stats.USED.getOrCreateStat(stack.getItem()));
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        player.getEatSound(stack), SoundCategory.PLAYERS, 1.0F,
                        1.0F + (world.random.nextFloat() - world.random.nextFloat()) * 0.4F);

                if (!player.getAbilities().creativeMode) {
                    stack.decrement(1);
                }

                player.emitGameEvent(GameEvent.EAT);

                // Cancel the original vanilla method execution.
                // This ensures vanilla effects (like rotten flesh poisoning) do not trigger
                // unless explicitly defined in our datapack configuration.
                cir.setReturnValue(stack);
            }
        }
    }
}