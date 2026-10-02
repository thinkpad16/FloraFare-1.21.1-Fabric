package net.tend1tnuy.florafare.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.NaturalRegeneration;
import net.tend1tnuy.florafare.food.RegenMath;
import net.tend1tnuy.florafare.food.SaturationCap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Florafare's hooks into the hunger manager: the saturation ceiling, and natural
 * regeneration (the delay after damage, the regeneration curve, the exhaustion
 * multiplier and the {@code florafare:natural_regeneration} attribute).
 *
 * <p>The regeneration hooks are {@code @WrapOperation}s and argument modifiers, never
 * {@code @Redirect}s or overwrites, for the reason {@code LivingEntityEatMixin} spells out:
 * {@code HungerManager#update} is exactly the method another hunger mod wants (Hunger
 * Tweaks wraps the same {@code heal} call), and wrappers compose where redirects collide.
 * {@code update} itself only ever runs on the logical server ({@code PlayerEntity#tick}
 * calls it behind {@code !isClient}), as does {@code addExhaustion} through
 * {@code PlayerEntity#addExhaustion}.
 */
@Mixin(HungerManager.class)
public abstract class HungerManagerMixin {

    /** Ticks since the last curve heal; see {@link #florafare$curveRegen}. */
    @Unique
    private int florafare$curveTimer = 0;

    /**
     * Lets {@code maxSaturation} replace vanilla's saturation ceiling.
     *
     * <p>{@code addInternal} is the one place the game bounds saturation: every bite (vanilla's
     * {@code eat}, Florafare's datapack values through {@code add}) ends there in
     * {@code clamp(saturation + current, 0, foodLevel)}. Only that upper bound is swapped, so
     * the floor of 0 and the hunger clamp next to it are untouched. Exhaustion and NBT loading
     * never clamp saturation, so a raised ceiling survives both.
     */
    @ModifyArg(
            method = "addInternal",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(FFF)F"),
            index = 2
    )
    private float florafare$saturationCeiling(float vanillaCeiling) {
        return SaturationCap.ceiling(FlorafareConfig.maxSaturation, vanillaCeiling);
    }

    /**
     * Holds vanilla's natural regeneration back during the delay after damage, and turns it
     * off entirely in curve mode.
     *
     * <p>Both regeneration branches of {@code update} — fast at a full bar with saturation,
     * slow at 18 and up — ask {@code canFoodHeal()}, and this wraps both calls. Saying no
     * sends {@code update} on to its starvation check (which needs an empty bar) or to
     * resetting its regeneration timer, so hunger loss, exhaustion and starvation are left
     * exactly as they were; only the healing waits. The original is asked first, so another
     * mod's wrapper and vanilla's own "alive and hurt" test still decide when it says no.
     */
    @WrapOperation(
            method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;canFoodHeal()Z")
    )
    private boolean florafare$gateNaturalRegen(PlayerEntity player, Operation<Boolean> original) {
        return original.call(player) && NaturalRegeneration.allowsVanillaRegen(player);
    }

    /**
     * Scales vanilla's regeneration heals by {@code florafare:natural_regeneration}.
     *
     * <p>Those two {@code heal} calls inside {@code update} are the only ones wrapped, so the
     * attribute affects natural regeneration and nothing else. Their exhaustion cost is left
     * alone: a buff that speeds regeneration up should not also make it dearer per heal.
     */
    @WrapOperation(
            method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;heal(F)V")
    )
    private void florafare$scaleNaturalHeal(PlayerEntity player, float amount, Operation<Void> original) {
        original.call(player, NaturalRegeneration.scaleVanillaHeal(player, amount));
    }

    /**
     * Florafare's own regeneration in curve mode, every
     * {@link RegenMath#CURVE_INTERVAL_TICKS} ticks, after vanilla's {@code update} has done
     * its exhaustion and starvation bookkeeping for the tick.
     *
     * <p>A timer per hunger manager, i.e. per player, so players heal on their own cadence
     * rather than all on the same server tick. It is reset while curve mode is off, so
     * switching to it with {@code /reload} starts everyone from a full interval.
     */
    @Inject(method = "update", at = @At("TAIL"))
    private void florafare$curveRegen(PlayerEntity player, CallbackInfo ci) {
        if (!NaturalRegeneration.isCurveMode()) {
            florafare$curveTimer = 0;
            return;
        }
        if (++florafare$curveTimer < RegenMath.CURVE_INTERVAL_TICKS) return;
        florafare$curveTimer = 0;
        NaturalRegeneration.curveHeal(player, (HungerManager) (Object) this);
    }

    /**
     * {@code exhaustionMultiplier}, applied to every exhaustion addition in both modes:
     * sprinting, jumping, mining, taking damage and the cost of regeneration alike. Only
     * positive amounts are scaled (see {@link RegenMath#scaleExhaustion}).
     */
    @ModifyVariable(method = "addExhaustion", at = @At("HEAD"), argsOnly = true)
    private float florafare$scaleExhaustion(float exhaustion) {
        return RegenMath.scaleExhaustion(exhaustion, FlorafareConfig.exhaustionMultiplier);
    }
}
