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
 * and the estimated-health preview.
 *
 * <p>Those three cancels only bind AppleSkin's own renderers, though, while
 * {@code FoodValuesEvent} is public API that any mod in a pack may read and draw for
 * itself. So an undiscovered dish reports no food values at all through that event
 * either, and the gate holds even where the cancel flag is never looked at — see
 * {@link #onFoodValues}.
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
     * What an undiscovered dish is worth, as far as anything reading AppleSkin's numbers
     * is concerned: nothing. See {@link #onFoodValues}.
     */
    private static final FoodComponent UNKNOWN_FOOD =
            new FoodComponent.Builder().nutrition(0).saturationModifier(0.0f).build();

    /**
     * Swaps in the datapack-configured nutrition and saturation, so everything AppleSkin
     * draws — the tooltip numbers and the hunger-bar preview while holding food — matches
     * what eating the item will really do.
     *
     * <p>For an undiscovered dish it reports nothing at all instead, which is the second
     * half of the discovery gate and the half that does not depend on anyone's
     * cooperation. The three cancels above only bind AppleSkin's own three renderers;
     * this event, by contrast, is public API that anything in a pack may read and draw
     * for itself, and a cancel flag another mod never sees cannot stop it. So the answer
     * itself is withheld rather than only the drawing of it.
     *
     * <p>It also makes AppleSkin hide the row on its own terms: its tooltip component is
     * only added when {@code hungerBars > 0}, and that is {@code ceil(nutrition / 2)} —
     * zero nutrition, no row, whatever happens to the cancel flag.
     *
     * <p>Zeroes rather than the real numbers is a change of mind from "cancel the render,
     * never lie about the values". The reasoning was that a zero is a number other
     * consumers would go on to compute with — but every one of those consumers is a
     * display, the alternative is handing them the exact answer the journal asks the
     * player to find out by eating, and a dish reading "0" next to "Unrecorded dish" is
     * plainly "not known yet" rather than a claim about the food.
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

        if (!BuffDescription.isDiscovered(stack, data)) {
            event.defaultFoodComponent  = UNKNOWN_FOOD;
            event.modifiedFoodComponent = UNKNOWN_FOOD;
            return;
        }

        // An entry that sets neither nutrition nor saturation leaves the bite's hunger to
        // vanilla, so whatever AppleSkin worked out for the item is already the answer.
        if (data.inheritsHunger()) return;

        event.defaultFoodComponent  = viewOf(data, stack);
        event.modifiedFoodComponent = event.defaultFoodComponent;
    }

    /**
     * The FoodComponent AppleSkin should draw for a config, memoized per config.
     *
     * <p>This event fires once per frame for the held stack and again for every food the
     * cursor passes over, and the component it wants is a pure function of two numbers on
     * an immutable, shared config object — so building a fresh one (plus the builder and
     * the empty effects list behind it) every time was pure garbage. Keyed by identity on
     * purpose: configs are shared instances handed out by the resolution cache.
     *
     * <p><b>Dropped whenever that cache is.</b> The class comment above used to promise
     * this and nothing did it: the map was only ever reset once it passed 4096 entries, so
     * every config replaced by a reload, a config sync or a {@code /florafare edit} stayed
     * reachable from here for the rest of the session. The map now remembers
     * {@link FoodBuffManager#configGeneration()} — bumped by every invalidation, on the
     * client's sync path and the integrated server's alike — and starts over when it moves.
     * Checked here rather than called from the manager, which is common code that must
     * never load an AppleSkin class on a dedicated server.
     *
     * <p>Only an entry that sets both halves itself is cached. One that inherits either
     * half from the eaten item is shared by every item its target covers — a #tag entry,
     * template:default — and resolves to a different answer for each, so it is built per
     * stack instead. That is the rarer shape, and the build is a builder and a record.
     */
    private static Map<FoodBuffData, FoodComponent> viewCache = new IdentityHashMap<>();

    /** The {@link FoodBuffManager#configGeneration()} {@link #viewCache} was filled under. */
    private static int viewCacheGeneration = Integer.MIN_VALUE;

    private static synchronized FoodComponent viewOf(FoodBuffData data, ItemStack stack) {
        if (!data.hasNutrition() || !data.hasSaturation()) {
            return viewFor(data.effectiveNutrition(stack), data.effectiveSaturation(stack));
        }

        int generation = FoodBuffManager.configGeneration();
        if (generation != viewCacheGeneration) {
            viewCache = new IdentityHashMap<>();
            viewCacheGeneration = generation;
        }

        FoodComponent cached = viewCache.get(data);
        if (cached != null) return cached;

        // Still bounded: within one generation the map only grows with the number of
        // distinct configs in the pack, but a pathological pack should not be able to
        // grow it without limit.
        if (viewCache.size() > 4096) viewCache = new IdentityHashMap<>();

        // Through the effective helpers like every other surface — for an entry that sets
        // both halves they are the raw fields, but nothing outside FoodBuffData should
        // have to know that.
        FoodComponent view = viewFor(data.effectiveNutrition(stack), data.effectiveSaturation(stack));
        viewCache.put(data, view);
        return view;
    }

    /**
     * A FoodComponent AppleSkin can draw, for a nutrition and a saturation modifier.
     *
     * <p>Negative values are clamped to zero. Florafare allows a food that drains hunger,
     * but AppleSkin's row and its hunger-bar preview count drumsticks up from the current
     * level and have no way to draw a loss — handed a negative they draw nonsense at best.
     * A zero draws nothing, which is honest: Florafare's own tooltip spells the drain out
     * in red, and it does so even with AppleSkin installed.
     */
    private static FoodComponent viewFor(int nutrition, float saturationModifier) {
        int shownNutrition = Math.max(0, nutrition);
        float shownModifier = shownNutrition == 0 || !(saturationModifier > 0) ? 0.0f : saturationModifier;
        return new FoodComponent.Builder()
                .nutrition(shownNutrition)
                .saturationModifier(shownModifier)
                .build();
    }
}
