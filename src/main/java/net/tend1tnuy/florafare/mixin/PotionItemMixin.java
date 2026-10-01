package net.tend1tnuy.florafare.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PotionItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code potion:} entries do something.
 *
 * <p>The loader has always accepted them and the journal has always listed them, but
 * nothing ever looked one up when a potion was drunk — potions are not food, so the eat
 * path never saw them — and a pack author writing one got a journal row that could never
 * be unlocked by playing. This is the missing half: drinking a potion whose base potion
 * has an entry grants that entry's buff and effects, exactly as eating a configured food
 * does, and records the {@code potion:} target as discovered.
 *
 * <p>Purely additive. The potion's own effects, the bottle, the stat and the criterion
 * are all vanilla's; nothing is cancelled and no hunger changes hands.
 *
 * <p>At HEAD because the stack has to be intact — {@code finishUsing} decrements it — and
 * server players only, for the same reason the eat hook skips fake players: Florafare has
 * no overlay, journal or sync to give anything else.
 */
@Mixin(PotionItem.class)
public abstract class PotionItemMixin {

    @Inject(method = "finishUsing", at = @At("HEAD"))
    private void florafare$applyPotionBuff(ItemStack stack, World world, LivingEntity user,
                                           CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClient || !(user instanceof ServerPlayerEntity player)) return;

        FoodBuffData data = FoodBuffManager.getPotionConfig(stack);
        if (data == null) return;

        String target = FoodBuffManager.potionTarget(stack);
        PlayerFoodComponent component =
                ((IFoodComponentProvider) player).florafare$getFoodComponent();

        // The target itself is the journal entry: every potion is the one item
        // minecraft:potion, so there is no item id that could stand for it.
        component.unlockFood(target);

        PlayerFoodComponent.BiteOutcome outcome = component.drinkPotion(stack, target, data);
        if (outcome == PlayerFoodComponent.BiteOutcome.SLOTS_FULL
                && FlorafareConfig.notifyOnFullBuffSlots) {
            player.sendMessage(Text.translatable("message.florafare.buff_slots_full",
                    PlayerFoodComponent.getMaxBuffSlots()), true);
        }
    }
}
