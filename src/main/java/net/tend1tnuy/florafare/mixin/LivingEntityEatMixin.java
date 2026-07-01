package net.tend1tnuy.florafare.mixin;

import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import net.minecraft.registry.Registries;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class LivingEntityEatMixin {

    @Inject(method = "eatFood", at = @At("HEAD"), cancellable = true)
    private void onEatFood(World world, ItemStack stack, FoodComponent food, CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClient && (Object) this instanceof ServerPlayerEntity player) {

            FoodBuffData data = FoodBuffManager.getConfig(stack);

            if (data != null) {
                PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();

                component.unlockFood(data.target());

                player.getHungerManager().add(data.nutrition(), data.saturation());

                component.tryAddBuff(stack, data);

                if (FlorafareConfig.consumptionLogging != FlorafareConfig.LogLevel.NONE) {
                    String playerName = player.getName().getString();
                    String itemId = Registries.ITEM.getId(stack.getItem()).toString();

                    if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.ALL) {
                        Florafare.LOGGER.info(
                                "[Florafare Consume] Player {} consumed {}. Buff: {} | Health: +{} | Saturation: {} | Nutrition: {} | Duration: {}t",
                                playerName, itemId, data.target(), data.healthBonus(), data.saturation(), data.nutrition(), data.duration()
                        );
                    } else if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.REDUCED) {
                        if (data.healthBonus() > 0) {
                            Florafare.LOGGER.info(
                                    "[Florafare Exploit Tracker] Player {} recovered health (+{}) by consuming {}",
                                    playerName, data.healthBonus(), itemId
                            );
                        }
                    }
                }

                // Replicate the eat sound from the cancelled LivingEntity#eatFood path.
                // NOTE: the USED stat is intentionally NOT incremented here. PlayerEntity#eatFood
                // (the override that calls into this method via super) already increments it, and
                // that code still runs — incrementing it again would double-count consumption.
                world.playSound(
                        null,
                        player.getX(), player.getY(), player.getZ(),
                        player.getEatSound(stack),
                        SoundCategory.PLAYERS,
                        1.0F,
                        1.0F + (world.random.nextFloat() - world.random.nextFloat()) * 0.4F
                );

                // Handle item stack decrementing AND recipe remainder (e.g., returning wooden bowls)
                if (!player.getAbilities().creativeMode) {
                    ItemStack remainder = stack.getItem().hasRecipeRemainder() ? new ItemStack(stack.getItem().getRecipeRemainder()) : ItemStack.EMPTY;
                    stack.decrement(1);

                    if (!remainder.isEmpty()) {
                        if (stack.isEmpty()) {
                            player.emitGameEvent(GameEvent.EAT);
                            cir.setReturnValue(remainder);
                            return;
                        } else if (!player.getInventory().insertStack(remainder)) {
                            player.dropItem(remainder, false);
                        }
                    }
                }

                player.emitGameEvent(GameEvent.EAT);

                cir.setReturnValue(stack);
            }
        }
    }
}