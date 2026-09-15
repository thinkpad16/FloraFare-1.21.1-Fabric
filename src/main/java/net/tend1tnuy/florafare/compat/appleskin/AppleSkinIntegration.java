package net.tend1tnuy.florafare.compat.appleskin;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import net.tend1tnuy.florafare.client.BuffDescription;

import java.util.IdentityHashMap;
import java.util.Map;
import squeek.appleskin.api.AppleSkinApi;
import squeek.appleskin.api.event.FoodValuesEvent;
import squeek.appleskin.api.event.HUDOverlayEvent;
import squeek.appleskin.api.event.TooltipOverlayEvent;

/**
 * AppleSkin compatibility.
 *
 * <p>The job here is narrow on purpose: tell AppleSkin the numbers Florafare will
 * actually apply, and then stay out of its way. Florafare replaces a food's vanilla
 * nutrition and saturation with its datapack values, so without this hook AppleSkin
 * would confidently draw the vanilla ones — the drumstick preview and the tooltip would
 * both be wrong for every managed food on the server.
 *
 * <p>The second job is to keep AppleSkin inside the discovery gate. A food's nutrition
 * and saturation are part of what the journal asks the player to find out by eating it,
 * so AppleSkin drawing them on an undiscovered dish handed over exactly what Florafare's
 * own tooltip was busy withholding two lines above — the locked placeholder said
 * "unrecorded", and the drumstick row underneath printed the numbers anyway.
 *
 * <p>So the three AppleSkin surfaces that describe <em>one particular food</em> are
 * cancelled until that food has been eaten once: the tooltip row, the hunger-bar preview
 * and the estimated-health preview. Cancelling the render rather than reporting zeroes
 * is deliberate — a zero is a lie that other consumers of {@code FoodValuesEvent} would
 * go on to compute with, whereas an absent row is merely absent, and the line directly
 * above it on the same tooltip explains why.
 *
 * <p>The player's own saturation and exhaustion overlays are left alone. They describe
 * the player, not a dish, so there is nothing about them to discover — and they are a
 * large part of why AppleSkin is installed at all.
 *
 * <p>Foods Florafare does not manage are never touched: an excluded item, or one no
 * config resolves to, keeps whatever AppleSkin worked out for it.
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

        // Registered here and not in FlorafareClient: AppleSkin fires this entrypoint
        // from its own ClientModInitializer, so these three client-only event classes
        // are never touched on a dedicated server.
        TooltipOverlayEvent.Pre.EVENT.register(AppleSkinIntegration::onTooltipOverlay);
        HUDOverlayEvent.HungerRestored.EVENT.register(AppleSkinIntegration::onHungerRestored);
        HUDOverlayEvent.HealthRestored.EVENT.register(AppleSkinIntegration::onHealthRestored);
    }

    /**
     * Whether AppleSkin should say nothing about this stack: Florafare manages it, and
     * this player has not eaten it yet.
     *
     * <p>Answers false for anything Florafare does not manage, so the gate can never
     * blank out a food that belongs to another mod.
     */
    private static boolean isUndiscoveredManagedFood(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data == null) return false;
        return !BuffDescription.isDiscovered(stack, data);
    }

    /** Hides the drumstick/saturation row on the tooltip of an undiscovered dish. */
    private static void onTooltipOverlay(TooltipOverlayEvent.Pre event) {
        if (isUndiscoveredManagedFood(event.itemStack)) event.isCanceled = true;
    }

    /** Hides the hunger-bar preview drawn while holding an undiscovered dish. */
    private static void onHungerRestored(HUDOverlayEvent.HungerRestored event) {
        if (isUndiscoveredManagedFood(event.itemStack)) event.isCanceled = true;
    }

    /** Hides the estimated-health preview for an undiscovered dish. */
    private static void onHealthRestored(HUDOverlayEvent.HealthRestored event) {
        if (isUndiscoveredManagedFood(event.itemStack)) event.isCanceled = true;
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
