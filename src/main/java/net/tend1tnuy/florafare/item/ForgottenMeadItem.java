package net.tend1tnuy.florafare.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;

public class ForgottenMeadItem extends Item {
    public ForgottenMeadItem(Settings settings) {
        super(settings);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        // Викликаємо суперклас, який зменшить стак на 1
        ItemStack result = super.finishUsing(stack, world, user);

        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            PlayerFoodComponent comp = ((IFoodComponentProvider) player).florafare$getFoodComponent();

            // Якщо видалення успішне, можна додати звук/ефект за бажанням
            comp.removeLastBuff();
        }

        // Повертаємо пусту пляшечку
        if (user instanceof PlayerEntity player && !player.getAbilities().creativeMode) {
            ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
            if (result.isEmpty()) {
                return bottle;
            } else {
                if (!player.getInventory().insertStack(bottle)) {
                    player.dropItem(bottle, false);
                }
            }
        }

        return result;
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.DRINK; // Анімація пиття
    }
}