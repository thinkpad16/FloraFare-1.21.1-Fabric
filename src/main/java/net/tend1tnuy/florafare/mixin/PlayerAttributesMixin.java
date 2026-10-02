package net.tend1tnuy.florafare.mixin;

import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.player.PlayerEntity;
import net.tend1tnuy.registry.FlorafareAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts Florafare's regeneration attributes into every player's default attribute container.
 *
 * <p>{@code createPlayerAttributes()} is what {@code DefaultAttributeRegistry} builds the
 * player's container from, once, during bootstrap and before any mod initializer runs; see
 * {@link FlorafareAttributes} for why the attributes register themselves from here rather
 * than from {@code onInitialize}. An inject at RETURN adds to the builder vanilla (and any
 * other mod's injector) already filled, so it composes with every other mod doing the same.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerAttributesMixin {

    @Inject(method = "createPlayerAttributes", at = @At("RETURN"))
    private static void florafare$addRegenAttributes(CallbackInfoReturnable<DefaultAttributeContainer.Builder> cir) {
        FlorafareAttributes.addToPlayer(cir.getReturnValue());
    }
}
