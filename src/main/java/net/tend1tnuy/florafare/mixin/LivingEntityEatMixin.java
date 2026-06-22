package net.tend1tnuy.florafare.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.component.type.FoodComponent;
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

@Mixin(LivingEntity.class)
public abstract class LivingEntityEatMixin {

    @Inject(method = "eatFood", at = @At("HEAD"), cancellable = true)
    private void onEatFood(World world, ItemStack stack, FoodComponent food, CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClient && (Object) this instanceof ServerPlayerEntity player) {

            FoodBuffData data = FoodBuffManager.getConfig(stack);

            if (data != null) {
                PlayerFoodComponent comp = ((IFoodComponentProvider) player).florafare$getFoodComponent();

                // === ВИПРАВЛЕНО: Відкриваємо їжу за правильним target ID (напр. item:minecraft:apple) ===
                comp.unlockFood(data.target());

                // 1. Застосовуємо ситість
                player.getHungerManager().add(data.nutrition(), data.saturation());

                // 2. Додаємо баф у слоти
                comp.tryAddBuff(stack, data);

                // 3. Ванільні звуки та зменшення предмета
                player.incrementStat(Stats.USED.getOrCreateStat(stack.getItem()));
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        player.getEatSound(stack), SoundCategory.PLAYERS, 1.0F,
                        1.0F + (world.random.nextFloat() - world.random.nextFloat()) * 0.4F);

                if (!player.getAbilities().creativeMode) {
                    stack.decrement(1);
                }

                player.emitGameEvent(GameEvent.EAT);

                cir.setReturnValue(stack);
            }
        }
    }
}