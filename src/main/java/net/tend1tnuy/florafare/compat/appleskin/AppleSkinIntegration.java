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
        TooltipOverlayEvent.Pre.EVENT.register(AppleSkinIntegration::onTooltipOverlayPre);
    }

    /**
     * Hides AppleSkin's hunger/saturation tooltip icons for Florafare-managed
     * foods until the player has discovered them (eaten them once) — the same
     * gating as the food journal, so the values stay a surprise beforehand.
     * Only fires client-side (tooltip rendering), so the client access is safe.
     */
    private static void onTooltipOverlayPre(TooltipOverlayEvent.Pre event) {
        FoodBuffData data = FoodBuffManager.getConfig(event.itemStack);
        if (data == null) {
            return; // not Florafare-managed: leave AppleSkin's default behaviour
        }

        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }

        Set<String> discovered = ((IFoodComponentProvider) player).florafare$getFoodComponent().getDiscoveredFoods();
        String itemId = Registries.ITEM.getId(event.itemStack.getItem()).toString();
        // The target check covers discoveries recorded before they were keyed by item id.
        if (!discovered.contains(itemId) && !discovered.contains(data.target())) {
            event.isCanceled = true;
        }
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
