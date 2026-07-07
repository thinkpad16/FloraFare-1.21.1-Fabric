package net.tend1tnuy.florafare.mixin;

import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
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

/**
 * Replaces the vanilla food consumption logic for foods managed by Florafare.
 *
 * The injection cancels PlayerEntity#eatFood at HEAD, so this mixin must replicate
 * every side effect of the cancelled vanilla path (PlayerEntity#eatFood and the
 * LivingEntity#eatFood super call): USED stat, burp sound, CONSUME_ITEM criterion,
 * FoodComponent status effects, eat sound, stack decrement, container returns
 * (usingConvertsTo / recipe remainder), and the EAT game event — while substituting
 * our configured nutrition/saturation for the vanilla hunger application.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityEatMixin {

    @Inject(method = "eatFood", at = @At("HEAD"), cancellable = true)
    private void onEatFood(World world, ItemStack stack, FoodComponent food, CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClient && (Object) this instanceof ServerPlayerEntity player) {

            FoodBuffData data = FoodBuffManager.getConfig(stack);

            if (data != null) {
                PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();

                // Record the concrete item, not data.target(): tag/namespace/template
                // targets have no journal entry of their own — the journal lists foods
                // per item, so each one must be discovered individually.
                component.unlockFood(Registries.ITEM.getId(stack.getItem()).toString());

                // Apply configured nutrition and saturation instead of the vanilla values.
                // NOTE: HungerManager#add treats the float as a saturation MODIFIER
                // (added saturation = nutrition * modifier * 2), matching the datapack semantics.
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

                // --- Replicate the cancelled PlayerEntity#eatFood side effects ---
                player.incrementStat(Stats.USED.getOrCreateStat(stack.getItem()));
                world.playSound(
                        null,
                        player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_PLAYER_BURP,
                        SoundCategory.PLAYERS,
                        0.5F,
                        world.random.nextFloat() * 0.1F + 0.9F
                );
                Criteria.CONSUME_ITEM.trigger(player, stack);

                // --- Replicate the cancelled LivingEntity#eatFood (super) side effects ---
                // NOTE: the FoodComponent's own status effects (applyFoodEffects) are
                // intentionally NOT replicated — for Florafare-managed foods, all effects
                // are defined exclusively via datapack configs.
                world.playSound(
                        null,
                        player.getX(), player.getY(), player.getZ(),
                        player.getEatSound(stack),
                        SoundCategory.NEUTRAL,
                        1.0F,
                        1.0F + (world.random.nextFloat() - world.random.nextFloat()) * 0.4F
                );

                // Handle stack decrementing and container returns: the FoodComponent's
                // usingConvertsTo (e.g., stew bowls, honey bottles) or the item's
                // recipe remainder as a fallback.
                if (!player.getAbilities().creativeMode) {
                    ItemStack remainder = food.usingConvertsTo().map(ItemStack::copy)
                            .orElseGet(() -> stack.getItem().hasRecipeRemainder()
                                    ? new ItemStack(stack.getItem().getRecipeRemainder())
                                    : ItemStack.EMPTY);
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
