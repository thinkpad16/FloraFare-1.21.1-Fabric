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
 * Injects into PlayerEntity to attach, tick, and persist the custom food component.
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
     * Updates the custom food component state during the player's tick loop.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        this.foodComponent.tick();
    }

    /**
     * Persists the component's data into the player's NBT tag when saving.
     */
    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void onWriteCustomDataToNbt(NbtCompound nbt, CallbackInfo ci) {
        this.foodComponent.writeToNbt(nbt);
    }

    /**
     * Loads the component's data from the player's NBT tag when reading.
     */
    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void onReadCustomDataFromNbt(NbtCompound nbt, CallbackInfo ci) {
        this.foodComponent.readFromNbt(nbt);
    }
}