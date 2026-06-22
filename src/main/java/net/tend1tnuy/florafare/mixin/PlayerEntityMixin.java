package net.tend1tnuy.florafare.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link PlayerEntity} to attach and synchronize the custom food component.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin implements IFoodComponentProvider {

    @Unique
    private final PlayerFoodComponent foodComponent = new PlayerFoodComponent((PlayerEntity) (Object) this);

    @Override
    public PlayerFoodComponent florafare$getFoodComponent() {
        return this.foodComponent;
    }

    /**
     * Injects into the player tick loop to update the food component state.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        this.foodComponent.tick();
    }

    /**
     * Injects into NBT writing to persist food component data.
     */
    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void onWriteNbt(NbtCompound nbt, CallbackInfo ci) {
        this.foodComponent.writeToNbt(nbt);
    }

    /**
     * Injects into NBT reading to load food component data.
     */
    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void onReadNbt(NbtCompound nbt, CallbackInfo ci) {
        this.foodComponent.readFromNbt(nbt);
    }
}