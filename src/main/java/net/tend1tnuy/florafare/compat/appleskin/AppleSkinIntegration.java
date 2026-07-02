package net.tend1tnuy.florafare.compat.appleskin;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import squeek.appleskin.api.AppleSkinApi;
import squeek.appleskin.api.event.FoodValuesEvent;

/**
 * AppleSkin compatibility. Registered through AppleSkin's own "appleskin" entrypoint,
 * so this class is only ever loaded when AppleSkin is installed.
 *
 * AppleSkin reads the item's vanilla {@link FoodComponent} to render the hunger/saturation
 * tooltip and HUD preview. Florafare never modifies that component (it applies its values
 * at eat-time instead), so without this hook AppleSkin shows the vanilla/base values rather
 * than ours. Here we override AppleSkin's "modified" food component with the values resolved
 * from {@link FoodBuffManager}, which are replicated to the client via FoodConfigSyncPayload.
 */
public class AppleSkinIntegration implements AppleSkinApi {

    @Override
    public void registerEvents() {
        FoodValuesEvent.EVENT.register(AppleSkinIntegration::onFoodValues);
    }

    private static void onFoodValues(FoodValuesEvent event) {
        ItemStack stack = event.itemStack;
        if (stack == null || stack.isEmpty()) {
            return;
        }

        FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data == null) {
            return;
        }

        // Rebuild only the values AppleSkin displays. Other properties (effects, eat time)
        // don't affect the hunger/saturation readout, so a minimal component is sufficient.
        FoodComponent florafareValues = new FoodComponent.Builder()
                .nutrition(data.nutrition())
                .saturationModifier(data.saturation())
                .build();

        // Override BOTH components: AppleSkin renders defaultFoodComponent underneath
        // modifiedFoodComponent (as a "this value was modified" comparison), so leaving
        // the default at vanilla makes the tooltip show vanilla and our values combined.
        // Florafare fully replaces the food values, so there is no "default" to compare to.
        event.defaultFoodComponent = florafareValues;
        event.modifiedFoodComponent = florafareValues;
    }
}
