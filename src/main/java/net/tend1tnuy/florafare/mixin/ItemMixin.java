package net.tend1tnuy.florafare.mixin;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Item.class)
public abstract class ItemMixin {

    @Inject(method = "appendTooltip", at = @At("TAIL"))
    private void florafare$hideOriginalFoodTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type, CallbackInfo ci) {
        if (FoodBuffManager.getConfig(stack) != null) {

            if (tooltip.size() > 1) {
                Text name = tooltip.get(0);
                tooltip.clear();
                tooltip.add(name);
            }
        }
    }
}