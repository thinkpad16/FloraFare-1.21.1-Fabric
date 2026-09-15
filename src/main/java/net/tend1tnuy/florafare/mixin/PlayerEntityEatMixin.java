package net.tend1tnuy.florafare.mixin;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
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
 * Adds Florafare's own work to eating: the journal unlock, the buff, the log line.
 *
 * <p><b>It no longer replaces vanilla eating.</b> This used to be an
 * {@code @At("HEAD") cancellable} injection that took over {@code PlayerEntity#eatFood}
 * outright and re-implemented every side effect by hand — the USED stat, the burp, the
 * {@code CONSUME_ITEM} criterion, the eat sound, the stack decrement, the bowl/bottle
 * return, the {@code EAT} game event. That worked, but it meant that for essentially
 * every food in the game the vanilla method never ran, and neither did any other mod's
 * hook on it: a {@code RETURN} injection, a {@code HungerManager#eat} redirect, a tail
 * hook. Food and hunger mods are exactly the mods most likely to be installed alongside
 * this one, and the failure was silent on both sides.
 *
 * <p>So the three things Florafare actually needs to change are now made surgically, and
 * everything else is left to Mojang:
 *
 * <ul>
 *   <li>the configured nutrition and saturation, via the {@code HungerManager#eat}
 *       redirect in {@link PlayerEntityMixin};</li>
 *   <li>the food's own vanilla status effects, via {@link LivingEntityEatMixin};</li>
 *   <li>the buff, the unlock and the logging — here, without cancelling anything.</li>
 * </ul>
 *
 * <p>Runs at HEAD rather than RETURN because the stack still has to exist: by the time
 * {@code eatFood} returns it has been decremented, and the last item of a stack leaves
 * {@code getItem()} answering {@code AIR}, which would record the wrong journal entry and
 * the wrong buff icon.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityEatMixin {

    @Inject(method = "eatFood", at = @At("HEAD"))
    private void florafare$applyFoodBuff(World world, ItemStack stack, FoodComponent food,
                                         CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClient) return;

        PlayerEntity self = (PlayerEntity) (Object) this;
        PlayerFoodComponent component =
                ((IFoodComponentProvider) self).florafare$getFoodComponent();

        // First thing, ahead of every early return below: until tryAddBuff says
        // otherwise, this bite belongs to vanilla and neither the hunger redirect nor
        // the effect suppression may touch it.
        component.beginBite();

        FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data == null) return;

        // Another mod's fake player, or anything else that is a PlayerEntity without
        // being a real connected one: Florafare has no buff overlay, no journal and no
        // sync for it, so it has nothing to put in vanilla's place and leaves the bite
        // alone — the same rule LivingEntityEatMixin applies to non-players.
        if (!(self instanceof ServerPlayerEntity player)) return;

        // Record the concrete item, not data.target(): tag/namespace/template targets
        // have no journal entry of their own — the journal lists foods per item, so each
        // one must be discovered individually.
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        component.unlockFood(itemId);

        // Returns false for two quite different reasons, and only one of them is a
        // "Florafare stays out of this bite":
        //
        //   * every slot is taken — Florafare is still managing the food, the player
        //     just cannot hold another buff, so the datapack's nutrition and the effect
        //     suppression stand. Not treated as anything worth telling the player about
        //     either: the overlay is on screen the whole time and already shows the
        //     slots are full;
        //   * a BUFF_APPLYING listener vetoed it — that is a mod asking Florafare to
        //     keep its hands off, so tryAddBuff records it and the bite reverts to
        //     vanilla in full.
        component.tryAddBuff(stack, data);

        florafare$logConsumption(player, itemId, data);
    }

    private static void florafare$logConsumption(ServerPlayerEntity player, String itemId,
                                                 FoodBuffData data) {
        if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.NONE) return;

        String playerName = player.getName().getString();
        if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.ALL) {
            Florafare.LOGGER.info(
                    "[Florafare Consume] Player {} consumed {}. Buff: {} | Health: +{} "
                            + "| Saturation: {} | Nutrition: {} | Duration: {}t",
                    playerName, itemId, data.target(), data.healthBonus(),
                    data.saturation(), data.nutrition(), data.duration());
        } else if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.REDUCED
                && data.healthBonus() > 0) {
            Florafare.LOGGER.info(
                    "[Florafare Exploit Tracker] Player {} recovered health (+{}) by consuming {}",
                    playerName, data.healthBonus(), itemId);
        }
    }
}
