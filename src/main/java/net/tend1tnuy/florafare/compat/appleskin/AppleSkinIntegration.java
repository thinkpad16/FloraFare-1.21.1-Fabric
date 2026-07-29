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

    /**
     * Спільний метод для перевірки, чи гравець вже дослідив цю їжу.
     */
    private static boolean isFoodDiscovered(ItemStack stack, FoodBuffData data) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return false;
        }

        Set<String> discovered = ((IFoodComponentProvider) player).florafare$getFoodComponent().getDiscoveredFoods();
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

        return discovered.contains(itemId) || discovered.contains(data.target());
    }

    /**
     * Приховує іконки ситості в тултипі (підказці при наведенні мишкою), якщо їжа не відкрита.
     */
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
     * Динамічно змінює значення, які AppleSkin використовує для малювання шкали HUD
     * (блимання "стегенець" при триманні їжі в руці).
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

        // Якщо їжу ще не їли — передаємо AppleSkin нульові значення.
        // Він побачить 0 ситості і просто не буде малювати прев'ю на HUD.
        if (!isFoodDiscovered(stack, data)) {
            florafareValues = new FoodComponent.Builder()
                    .nutrition(0)
                    .saturationModifier(0f)
                    .build();
        } else {
            // Якщо їжа досліджена — віддаємо правильні значення з датапаку.
            florafareValues = new FoodComponent.Builder()
                    .nutrition(data.nutrition())
                    .saturationModifier(data.saturation())
                    .build();
        }

        event.defaultFoodComponent = florafareValues;
        event.modifiedFoodComponent = florafareValues;
    }
}