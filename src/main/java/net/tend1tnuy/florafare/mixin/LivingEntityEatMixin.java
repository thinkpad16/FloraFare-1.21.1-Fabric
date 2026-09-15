package net.tend1tnuy.florafare.mixin;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses a food's own vanilla status effects when Florafare has replaced them.
 *
 * <p>This is the one piece of vanilla's eating sequence Florafare has to take away, and
 * it is taken away here — at the single call that grants those effects — rather than by
 * cancelling {@code PlayerEntity#eatFood} wholesale, which is how it used to be done.
 * The old approach meant Florafare replaced the entire method for essentially every food
 * in the game, so any other mod's {@code RETURN} injection, {@code HungerManager#eat}
 * redirect or tail hook on eating silently never ran. Everything else vanilla does when
 * a player eats — the USED stat, the burp, the eat sound, {@code CONSUME_ITEM}, the
 * stack decrement, {@code usingConvertsTo}, the {@code EAT} game event — now runs as
 * Mojang wrote it, and as every other mod expects it to.
 *
 * <p>An {@code @Inject} rather than a {@code @Redirect} on purpose: injections from
 * several mods compose, whereas two redirects on the same call site are a hard conflict,
 * and staying out of other food mods' way is the entire point of the change.
 * {@code applyFoodEffects} has exactly one caller ({@code LivingEntity#eatFood}), so the
 * reach is identical either way.
 *
 * <p>Only while {@link FlorafareConfig#respectVanillaFoodEffects} is off — which is
 * exactly when the mod's documented "effects come only from datapack configs" rule is in
 * force. With it on, the vanilla effects are meant to stack with the buff and are left
 * alone. It is also what {@code ItemStackMixin} keys its tooltip cleanup off, so the
 * tooltip and the behaviour cannot disagree.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEatMixin {

    @Inject(method = "applyFoodEffects", at = @At("HEAD"), cancellable = true)
    private void florafare$skipSupersededFoodEffects(FoodComponent food, CallbackInfo ci) {
        if (FlorafareConfig.respectVanillaFoodEffects) return;

        LivingEntity self = (LivingEntity) (Object) this;

        // Players only. Florafare's replacement buff is granted from
        // PlayerEntityEatMixin, which hooks PlayerEntity#eatFood — so for any other
        // LivingEntity that reaches LivingEntity#eatFood (a mod's creature, an entity fed
        // through tryEatFood) this hook took the food's vanilla effects away and put
        // nothing at all in their place. Taking something away is only correct where
        // something replaces it.
        if (!(self instanceof PlayerEntity player)) return;

        // The stack on its way down. LivingEntity#consumeItem passes its own
        // activeItemStack to finishUsing and only clears it afterwards, and the decrement
        // happens after this call, so during eatFood this is exactly the stack being
        // eaten, still intact. Anything that reaches eatFood by another route — a mod
        // feeding an entity directly — finds it empty, and vanilla is then left alone,
        // which is the right way round for a hook that only ever takes something away.
        ItemStack stack = self.getActiveItem();
        if (stack.isEmpty()) return;

        // Not a food Florafare manages (excluded, or owned by another mod): its vanilla
        // effects are the only ones it has, so they must still fire.
        if (FoodBuffManager.getConfig(stack) == null) return;

        // Managed, but this particular bite was handed back to vanilla — a BUFF_APPLYING
        // listener vetoed the buff whose effects would have replaced these ones. Same
        // rule as the non-player guard above, applied to the other way a replacement can
        // fail to happen.
        if (!PlayerFoodComponent.handlesCurrentBite(player)) return;

        ci.cancel();
    }
}
