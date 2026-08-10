package net.tend1tnuy.florafare.compat.appleskin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import squeek.appleskin.api.AppleSkinApi;
import squeek.appleskin.api.event.FoodValuesEvent;
import squeek.appleskin.api.event.TooltipOverlayEvent;

import java.util.Set;

/**
 * AppleSkin compatibility.
 */
public class AppleSkinIntegration implements AppleSkinApi {

    @Override
    public void registerEvents() {
        FoodValuesEvent.EVENT.register(AppleSkinIntegration::onFoodValues);
        TooltipOverlayEvent.Pre.EVENT.register(AppleSkinIntegration::onTooltipOverlayPre);
    }

    private static boolean isFoodDiscovered(ItemStack stack, FoodBuffData data) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return false;
        }

        Set<String> discovered = ((IFoodComponentProvider) player).florafare$getFoodComponent().getDiscoveredFoods();
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

        return discovered.contains(itemId) || discovered.contains(data.target());
    }

    /** Hides the saturation/nutrition tooltip overlay for foods the player hasn't discovered yet. */
    private static void onTooltipOverlayPre(TooltipOverlayEvent.Pre event) {
        FoodBuffData data = FoodBuffManager.getConfig(event.itemStack);
        if (data == null) {
            return;
        }

        if (!isFoodDiscovered(event.itemStack, data)) {
            event.isCanceled = true;
        }
    }

    /**
     * Overrides the nutrition/saturation values AppleSkin uses to draw its HUD overlay
     * (the drumstick-blink preview shown while holding food), with the datapack-configured
     * values instead of the item's vanilla FoodComponent.
     */
    private static void onFoodValues(FoodValuesEvent event) {
        ItemStack stack = event.itemStack;
        if (stack == null || stack.isEmpty()) {
            return;
        }

        FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data == null) {
            return;
        }

        FoodComponent florafareValues;

        // Zero values for undiscovered food — AppleSkin then sees 0 saturation and
        // simply skips drawing the HUD preview, keeping it hidden like the journal.
        if (!isFoodDiscovered(stack, data)) {
            florafareValues = new FoodComponent.Builder()
                    .nutrition(0)
                    .saturationModifier(0f)
                    .build();
        } else {
            florafareValues = new FoodComponent.Builder()
                    .nutrition(data.nutrition())
                    .saturationModifier(data.saturation())
                    .build();
        }

        event.defaultFoodComponent = florafareValues;
        event.modifiedFoodComponent = florafareValues;
    }
}