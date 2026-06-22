package net.tend1tnuy.florafare.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Example mixin class.
 * This is a template file and can be safely removed if not in use.
 */
@Mixin(MinecraftServer.class)
public class ExampleMixin {

    /**
     * Injected into the start of MinecraftServer.loadWorld()
     */
    @Inject(at = @At("HEAD"), method = "loadWorld")
    private void onInit(CallbackInfo info) {
        // Implementation here
    }
}