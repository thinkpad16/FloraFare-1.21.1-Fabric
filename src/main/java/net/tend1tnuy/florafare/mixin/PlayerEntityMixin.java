package net.tend1tnuy.florafare.mixin;

import net.minecraft.component.DataComponentTypes;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
     * Lifts vanilla's "only when hungry" restriction on eating. Two independent config
     * switches feed into this, the broader one first:
     *
     * <ul>
     *   <li>{@code allowEatingWhenFull} — every food becomes edible on a full hunger bar,
     *       so Florafare's buffs stay reachable no matter how fed the player is;</li>
     *   <li>{@code enableAlwaysEdibleOverride} — only foods whose resolved config carries
     *       the datapack's {@code always_edible} flag get the exemption.</li>
     * </ul>
     *
     * Everything past this gate is untouched: {@link PlayerEntityEatMixin} still applies
     * the configured nutrition/saturation (the hunger manager simply clamps whatever
     * overflows 20), the buff, the health bonus, synergies, and the journal unlock.
     *
     * canConsume() receives no stack, so the held stacks are what there is to inspect —
     * which matches the vanilla call site, {@code Item#use} passing the stack in the
     * hand being used.
     */
    @Inject(method = "canConsume", at = @At("HEAD"), cancellable = true)
    private void florafare$allowAlwaysEdible(boolean ignoreHunger, CallbackInfoReturnable<Boolean> cir) {
        // ignoreHunger == true already returns true in vanilla; nothing to override.
        if (ignoreHunger) return;

        PlayerEntity player = (PlayerEntity) (Object) this;
        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand  = player.getOffHandStack();

        if (net.tend1tnuy.florafare.config.FlorafareConfig.allowEatingWhenFull) {
            // Items other mods asked Florafare to ignore keep their vanilla gating, so
            // this never fights a mod that owns the item's hunger behaviour. The
            // "no food in hand" case is CakeBlock#tryEat, the one other vanilla caller.
            boolean holdsManagedFood = florafare$isManagedFood(mainHand) || florafare$isManagedFood(offHand);
            boolean holdsIgnoredFood = florafare$isIgnoredFood(mainHand) || florafare$isIgnoredFood(offHand);
            if (holdsManagedFood || !holdsIgnoredFood) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (net.tend1tnuy.florafare.config.FlorafareConfig.enableAlwaysEdibleOverride
                && (FoodBuffManager.isAlwaysEdible(mainHand) || FoodBuffManager.isAlwaysEdible(offHand))) {
            cir.setReturnValue(true);
        }
    }

    /** A food item Florafare is allowed to manage. */
    @Unique
    private static boolean florafare$isManagedFood(ItemStack stack) {
        return stack.contains(DataComponentTypes.FOOD) && !FoodBuffManager.isExcluded(stack);
    }

    /** A food item listed in {@code ignoredFoodItems} / excluded through the API. */
    @Unique
    private static boolean florafare$isIgnoredFood(ItemStack stack) {
        return stack.contains(DataComponentTypes.FOOD) && FoodBuffManager.isExcluded(stack);
    }

    /**
     * Suppresses vanilla hunger/saturation application for foods that Florafare manages.
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