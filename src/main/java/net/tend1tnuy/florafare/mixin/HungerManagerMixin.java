package net.tend1tnuy.florafare.mixin;

import net.minecraft.entity.player.HungerManager;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.SaturationCap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets {@code maxSaturation} replace vanilla's saturation ceiling.
 *
 * <p>{@code addInternal} is the one place the game bounds saturation: every bite (vanilla's
 * {@code eat}, Florafare's datapack values through {@code add}) ends there in
 * {@code clamp(saturation + current, 0, foodLevel)}. Only that upper bound is swapped, so
 * the floor of 0 and the hunger clamp next to it are untouched. Exhaustion and NBT loading
 * never clamp saturation, so a raised ceiling survives both.
 */
@Mixin(HungerManager.class)
public abstract class HungerManagerMixin {

    @ModifyArg(
            method = "addInternal",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(FFF)F"),
            index = 2
    )
    private float florafare$saturationCeiling(float vanillaCeiling) {
        return SaturationCap.ceiling(FlorafareConfig.maxSaturation, vanillaCeiling);
    }
}
