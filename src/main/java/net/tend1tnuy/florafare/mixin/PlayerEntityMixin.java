package net.tend1tnuy.florafare.mixin;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
     * Suppresses vanilla hunger/saturation application for foods that Florafare manages.
     *
     * On the SERVER, {@code PlayerEntityEatMixin} cancels PlayerEntity#eatFood at HEAD and
     * applies our configured values, so this redirect is never reached there. On the CLIENT,
     * however, eatFood still runs for local prediction and would apply the vanilla
     * FoodComponent values, making the hunger bar flicker to the wrong value until the next
     * server sync. Since the configs are replicated to the client (FoodConfigSyncPayload),
     * we skip the vanilla call here whenever a Florafare config exists; foods without a
     * config keep vanilla behaviour untouched.
     */
    @Redirect(
            method = "eatFood",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/HungerManager;eat(Lnet/minecraft/component/type/FoodComponent;)V"
            )
    )
    private void florafare$skipVanillaHunger(HungerManager hungerManager, FoodComponent food,
                                             World world, ItemStack stack, FoodComponent foodComponent) {
        net.tend1tnuy.florafare.food.FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data != null) {
            // Predict the configured values locally; the authoritative state
            // still arrives with the next server hunger sync.
            hungerManager.add(data.nutrition(), data.saturation());
            return;
        }
        hungerManager.eat(food);
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