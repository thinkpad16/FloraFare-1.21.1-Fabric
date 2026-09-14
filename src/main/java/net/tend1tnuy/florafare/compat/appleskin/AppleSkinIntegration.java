package net.tend1tnuy.florafare.compat.appleskin;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.IdentityHashMap;
import java.util.Map;
import squeek.appleskin.api.AppleSkinApi;
import squeek.appleskin.api.event.FoodValuesEvent;

/**
 * AppleSkin compatibility.
 *
 * <p>The job here is narrow on purpose: tell AppleSkin the numbers Florafare will
 * actually apply, and then stay out of its way. Florafare replaces a food's vanilla
 * nutrition and saturation with its datapack values, so without this hook AppleSkin
 * would confidently draw the vanilla ones — the drumstick preview and the tooltip would
 * both be wrong for every managed food on the server.
 *
 * <p>What it deliberately no longer does is hide AppleSkin. It used to cancel the tooltip
 * overlay and report zeroes for any food the player had not eaten yet, to keep the
 * journal's discovery mechanic airtight. On a server with AppleSkin installed that read
 * as AppleSkin being broken: its display simply vanished for most items in the inventory,
 * with nothing to explain why. The mystery Florafare means to preserve is what BUFF a
 * dish grants — that is still gated, in the buff block on the tooltip — not how filling
 * it is, which AppleSkin is installed precisely to show.
 *
 * <p>No conflict with Florafare's own tooltip block, either: AppleSkin appends its
 * component through {@code ItemTooltipCallback} and positions it relative to itself,
 * while Florafare inserts its lines just under the item name. The two never contend for
 * the same row.
 */
public class AppleSkinIntegration implements AppleSkinApi {

    @Override
    public void registerEvents() {
        FoodValuesEvent.EVENT.register(AppleSkinIntegration::onFoodValues);
    }

    /**
     * Swaps in the datapack-configured nutrition and saturation, so everything AppleSkin
     * draws — the tooltip numbers and the hunger-bar preview while holding food — matches
     * what eating the item will really do.
     */
    private static void onFoodValues(FoodValuesEvent event) {
        ItemStack stack = event.itemStack;
        if (stack == null || stack.isEmpty()) {
            return;
        }

        FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data == null) {
            // Not ours: an excluded item, or one no config resolves to. Whatever AppleSkin
            // worked out from the item itself is the truth for it.
            return;
        }

        event.defaultFoodComponent  = viewOf(data);
        event.modifiedFoodComponent = event.defaultFoodComponent;
    }

    /**
     * The FoodComponent AppleSkin should draw for a config, memoized per config.
     *
     * <p>This event fires once per frame for the held stack and again for every food the
     * cursor passes over, and the component it wants is a pure function of two numbers on
     * an immutable, shared config object — so building a fresh one (plus the builder and
     * the empty effects list behind it) every time was pure garbage. Keyed by identity on
     * purpose: configs are shared instances handed out by the resolution cache, and the
     * map is dropped whole whenever that cache is, so a reloaded datapack cannot be
     * served a stale entry.
     */
    private static Map<FoodBuffData, FoodComponent> viewCache = new IdentityHashMap<>();

    private static synchronized FoodComponent viewOf(FoodBuffData data) {
        FoodComponent cached = viewCache.get(data);
        if (cached != null) return cached;

        // Bounded by a plain reset rather than an eviction policy: the only thing that
        // grows this map is the number of distinct food configs in the pack, and a reload
        // replaces every one of them at once.
        if (viewCache.size() > 4096) viewCache = new IdentityHashMap<>();

        FoodComponent view = new FoodComponent.Builder()
                .nutrition(data.nutrition())
                .saturationModifier(data.saturation())
                .build();
        viewCache.put(data, view);
        return view;
    }
}
