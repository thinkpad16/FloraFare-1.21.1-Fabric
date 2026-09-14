package net.tend1tnuy.florafare.mixin;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
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
            //
            // Tested against "holds any food" rather than "holds an ignored food": today
            // every food is exactly one of managed/ignored, so the two happen to agree,
            // but only by accident. Should a third state ever appear — a food that is
            // neither — the old test would have granted the exemption to an item
            // Florafare had explicitly been kept away from.
            boolean holdsManagedFood = florafare$isManagedFood(mainHand) || florafare$isManagedFood(offHand);
            boolean holdsAnyFood     = florafare$isFood(mainHand) || florafare$isFood(offHand);
            if (holdsManagedFood || !holdsAnyFood) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (net.tend1tnuy.florafare.config.FlorafareConfig.enableAlwaysEdibleOverride
                && (FoodBuffManager.isAlwaysEdible(mainHand) || FoodBuffManager.isAlwaysEdible(offHand))) {
            cir.setReturnValue(true);
        }
    }

    /** Any edible item, whoever ends up managing it. */
    @Unique
    private static boolean florafare$isFood(ItemStack stack) {
        return stack.contains(DataComponentTypes.FOOD);
    }

    /** A food item Florafare is allowed to manage. */
    @Unique
    private static boolean florafare$isManagedFood(ItemStack stack) {
        return florafare$isFood(stack) && !FoodBuffManager.isExcluded(stack);
    }

    /**
     * Applies Florafare's configured nutrition and saturation in place of the item's own.
     *
     * <p>This is now <em>the</em> place the swap happens. It used to be dead code: the
     * old {@code PlayerEntityEatMixin} cancelled {@code eatFood} at HEAD before this
     * redirect could ever be reached, and did the same {@code add()} call itself. With
     * the cancellation gone, vanilla's own call site is redirected instead — which is
     * also the narrowest possible way to express "same method, different numbers".
     *
     * <p>{@code HungerManager#add} treats the float as a saturation <em>modifier</em>
     * (added saturation = nutrition x modifier x 2), which is the same meaning the
     * datapack field carries, so the value passes straight through.
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
            hungerManager.add(data.nutrition(), data.saturation());
            return;
        }
        hungerManager.eat(food);
    }

    /**
     * Drops the emptied bowl or bottle at the player's feet instead of deleting it when
     * their inventory is full.
     *
     * <p>Vanilla calls {@code insertStack} here and discards the boolean it returns, so
     * eating a stew with no free slot destroys the bowl. Florafare has always handed it
     * back rather than eating it, from back when this mixin re-implemented the whole
     * method; keeping that behaviour costs one redirect on the one call that decides it,
     * and losing it would have been a silent regression for anyone already used to it.
     *
     * <p>Returning true unconditionally is correct: the caller ignores the value, and by
     * that point the stack has either been inserted or dropped — in neither case is it
     * gone.
     */
    @Redirect(
            method = "eatFood",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerInventory;insertStack(Lnet/minecraft/item/ItemStack;)Z"
            )
    )
    private boolean florafare$dropContainerIfInventoryFull(PlayerInventory inventory,
                                                           ItemStack container) {
        if (inventory.insertStack(container)) return true;
        ((PlayerEntity) (Object) this).dropItem(container, false);
        return true;
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

        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self.getWorld() == null || self.getWorld().isClient) return;

        // Buff modifiers are temporary, so they are absent from the attributes vanilla
        // just deserialized and have to be rebuilt from our own saved buff list. The
        // strip first clears any persistent modifier left in the save by a pre-1.3
        // version, which would otherwise stack on top of the ones reapplied here.
        this.foodComponent.stripFlorafareModifiers();
        this.foodComponent.reapplyAttributes();

        // LivingEntity#readCustomDataFromNbt applied the saved health before any of
        // this, clamped against an unbuffed max — so a player who logged out at 24/24
        // came back at 20/24 and lost the hearts permanently. Now that the max-health
        // modifiers are back, restore what was saved (setHealth still clamps, so this
        // can only ever give back health the player genuinely had).
        if (nbt.contains("Health", NbtElement.NUMBER_TYPE)) {
            float savedHealth = nbt.getFloat("Health");
            if (savedHealth > self.getHealth()) self.setHealth(savedHealth);
        }
    }
}