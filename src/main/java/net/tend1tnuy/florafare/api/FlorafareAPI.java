package net.tend1tnuy.florafare.api;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodExclusions;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.food.NaturalRegeneration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Official API for the Florafare mod.
 * Provides safe methods to interact with the food buff system.
 */
public final class FlorafareAPI {

    private FlorafareAPI() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Retrieves the buff configuration for a specific ItemStack.
     *
     * @param stack The item to check.
     * @return The buff data, or null if no buff is configured.
     */
    @Nullable
    public static FoodBuffData getBuffData(@NotNull ItemStack stack) {
        return FoodBuffManager.getConfig(stack);
    }

    /**
     * Checks if the given ItemStack has a unique buff applied via commands.
     *
     * @param stack The item to check.
     * @return True if the item contains a custom command-based buff identifier.
     */
    public static boolean hasCustomCommandBuff(@NotNull ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customData != null && customData.copyNbt().contains("FlorafareBuffId");
    }

    /**
     * Applies a food buff to the player based on the provided configuration.
     *
     * <p>Server-side only; a call on the client is a no-op. Can fail for two reasons that
     * are not errors: every buff slot is taken, or a listener on
     * {@link FlorafareEvents#BUFF_APPLYING} vetoed it. Applies no hunger — that belongs to
     * eating — and is safe to call from a Florafare event listener, including in the
     * middle of a player's bite.
     *
     * <p>A hunger-only config ({@code duration <= 0}) grants no buff by design; its
     * status effects are applied and the call reports success.
     *
     * @param player The player receiving the buff.
     * @param stack  The consumed item, used for the buff's identity and HUD icon.
     * @param data   The buff configuration data; null is accepted and does nothing.
     * @return true if the buff is now active on the player, or a hunger-only config's
     *         effects were applied
     */
    public static boolean applyBuffToPlayer(@NotNull PlayerEntity player, @NotNull ItemStack stack,
                                            @Nullable FoodBuffData data) {
        if (data == null || player.getWorld().isClient()) return false;
        return ((IFoodComponentProvider) player).florafare$getFoodComponent()
                .tryAddBuff(stack, data);
    }

    /**
     * Tells Florafare to fully ignore an item id: it will no longer intercept eating
     * it, override its tooltip, mark it always-edible, or generate an auto-buff for
     * it — the item behaves as if Florafare were not installed. Use this from another
     * food/hunger mod's initializer when it wants to handle one of its own items
     * itself, instead of relying on Florafare's auto-generated fallback buff.
     *
     * <p>Safe to call on the client too, but it only takes lasting effect when called
     * server-side (or before the client connects); a dedicated server always
     * overwrites the client's exclusion set with its own on join and after
     * {@code /reload}.
     *
     * <p>Since 1.5 the argument is not limited to a single item id. It accepts any of
     * the forms {@link net.tend1tnuy.florafare.food.FoodExclusions} documents — an item
     * id, a whole mod ({@code "modid:*"}), or an item tag ({@code "#c:drinks"}) — so a
     * mod that owns all of its food can say so in one call instead of listing it.
     *
     * @param itemId The rule to exclude, e.g. {@code "modid:custom_stew"} or {@code "modid:*"}.
     */
    public static void excludeFood(@Nullable String itemId) {
        FoodBuffManager.excludeItem(itemId);
    }

    /**
     * Takes Florafare out of every food one mod adds, in one call. Exactly equivalent to
     * {@code excludeFood("<namespace>:*")}.
     *
     * @param namespace The mod id, e.g. {@code "loot_n_explore"}.
     */
    public static void excludeMod(@Nullable String namespace) {
        if (namespace != null) FoodBuffManager.excludeItem(namespace.trim() + ":*");
    }

    /** Reverses a previous {@link #excludeFood(String)} call. */
    public static void includeFood(@Nullable String itemId) {
        FoodBuffManager.includeItem(itemId);
    }

    /** Reverses a previous {@link #excludeMod(String)} call. */
    public static void includeMod(@Nullable String namespace) {
        if (namespace != null) FoodBuffManager.includeItem(namespace.trim() + ":*");
    }

    /**
     * Claims something back from whatever excluded it: Florafare manages it regardless
     * of {@code ignoredFoodItems}, the {@code #florafare:ignored} tag, or another mod's
     * {@link #excludeFood(String)} call.
     *
     * <p>For an addon that deliberately wants to buff one dish out of a mod Florafare
     * ships exclusions for. Takes the same forms as {@link #excludeFood(String)}.
     */
    public static void manageFoodAnyway(@Nullable String rule) {
        FoodExclusions.add(FoodExclusions.Effect.MANAGE, FoodExclusions.Source.API, rule);
    }

    /** Reverses a previous {@link #manageFoodAnyway(String)} call. */
    public static void stopManagingAnyway(@Nullable String rule) {
        FoodExclusions.remove(FoodExclusions.Effect.MANAGE, FoodExclusions.Source.API, rule);
    }

    /** Whether Florafare has been told to fully ignore this stack's item. */
    public static boolean isFoodExcluded(@NotNull ItemStack stack) {
        return FoodBuffManager.isExcluded(stack);
    }

    /**
     * Whether Florafare has been told to fully ignore this item — the allocation-free
     * form, for walking the item registry.
     */
    public static boolean isFoodExcluded(@NotNull net.minecraft.item.Item item) {
        return FoodBuffManager.isExcluded(item);
    }

    /**
     * Registers a food buff config in code instead of via a datapack JSON file.
     * Behaves exactly like a datapack entry: {@code target} accepts any of the
     * forms documented for the {@code id} field (item id, "#tag", "namespace:mod",
     * "template:default"), and normal priority rules apply against configs loaded
     * from datapacks.
     *
     * <p>Call this from your mod initializer so the config exists before players
     * connect. Already-connected clients only see the change after the next
     * {@code /reload} or rejoin, same as a datapack edit would require.
     */
    public static void registerFoodBuff(@NotNull String target, @NotNull FoodBuffData data) {
        String normalized = FoodBuffManager.normalizeTarget(target);
        FoodBuffManager.putApiConfig(normalized, data);
    }

    /**
     * Registers a food synergy in code instead of via a datapack JSON file.
     * See {@link #registerFoodBuff(String, FoodBuffData)} for the same timing caveat.
     */
    public static void registerSynergy(@NotNull FoodSynergyData data) {
        FoodSynergyManager.putApiSynergy(data);
    }

    /**
     * The buffs currently active on this player.
     *
     * <p>A genuine copy, not a read-only view of the live list. These four accessors used
     * to hand back unmodifiable wrappers around the component's own collections, which
     * the player tick mutates as buffs expire — so a caller iterating one across ticks,
     * or from anywhere but the server thread, could take a
     * {@link java.util.ConcurrentModificationException} through no fault of its own.
     */
    @NotNull
    public static List<ActiveFoodBuff> getActiveBuffs(@NotNull PlayerEntity player) {
        return List.copyOf(component(player).getActiveBuffs());
    }

    /** The synergies currently active on this player. Snapshot; see {@link #getActiveBuffs}. */
    @NotNull
    public static List<ActiveFoodBuff> getActiveSynergies(@NotNull PlayerEntity player) {
        return List.copyOf(component(player).getActiveSynergies());
    }

    /**
     * The aftermaths currently running on this player — what buffs and synergies that
     * have ended left behind. Snapshot; see {@link #getActiveBuffs}.
     *
     * <p>Each entry's {@link ActiveFoodBuff#getTarget()} is the source's config target (or
     * synergy id, with {@link ActiveFoodBuff#isSynergy()} true), and its recorded modifiers
     * are what it is holding. The aftermath applied is the one its source's definition has
     * <em>when the source ends</em>, looked up by that target — so a buff granted through
     * {@link #applyBuffToPlayer} from a {@link FoodBuffData} that was never registered has
     * no definition to look up, and leaves no aftermath.
     */
    @NotNull
    public static List<ActiveFoodBuff> getActiveAftermaths(@NotNull PlayerEntity player) {
        return List.copyOf(component(player).getActiveAftermaths());
    }

    /** Item ids this player has discovered (eaten at least once). Snapshot. */
    @NotNull
    public static Set<String> getDiscoveredFoods(@NotNull PlayerEntity player) {
        return Set.copyOf(component(player).getDiscoveredFoods());
    }

    /** Synergy ids this player has discovered (activated at least once). Snapshot. */
    @NotNull
    public static Set<String> getDiscoveredSynergies(@NotNull PlayerEntity player) {
        return Set.copyOf(component(player).getDiscoveredSynergies());
    }

    /**
     * Removes a single active buff, addressed either by the config target it came from
     * ("#c:foods/berry") or by the item that was eaten ("minecraft:sweet_berries").
     *
     * <p>The buff ends the ordinary way, so its aftermath (and that of any synergy it was
     * holding up) starts — removing a buff early is not a way around its downside.
     *
     * @return true if such a buff was active and has been removed
     */
    public static boolean removeBuff(@NotNull PlayerEntity player, @Nullable String targetOrItemId) {
        return component(player).removeBuff(targetOrItemId);
    }

    /**
     * Removes every active buff, synergy and aftermath from the player, same as
     * {@code /florafare clear}. Nothing removed this way leaves an aftermath: a clear is a
     * clean slate. {@link #removeBuff} is the one that ends a single buff the ordinary way.
     */
    public static void clearBuffs(@NotNull PlayerEntity player) {
        component(player).clearAllBuffs();
    }

    /**
     * Ends every aftermath running on the player — a cure for a hangover — and leaves
     * buffs and synergies alone. Status effects an aftermath granted when it started are
     * ordinary effects by now and stay; remove those the vanilla way if you need to.
     *
     * @return how many aftermaths were ended
     */
    public static int clearAftermaths(@NotNull PlayerEntity player) {
        return component(player).clearAftermaths();
    }

    /**
     * Marks an item as discovered for this player without granting its buff, unlocking
     * its journal entry and firing {@link FlorafareEvents#FOOD_DISCOVERED}.
     *
     * @return true if this item was newly discovered, false if it already was.
     */
    public static boolean unlockFood(@NotNull PlayerEntity player, @NotNull String itemId) {
        return component(player).unlockFood(itemId);
    }

    // -------------------------------------------------------------------------
    // NATURAL REGENERATION
    //
    // The delay after damage lives on the logical server, measured in server ticks. On the
    // client these read as "no delay" and change nothing.
    // -------------------------------------------------------------------------

    /**
     * Ticks left before this player's natural regeneration may resume after damage; 0 when
     * there is no delay. Counts the delay itself, whether or not an effect in
     * {@code #florafare:ignores_regen_delay} is currently letting regeneration through —
     * see {@link #isRegenDelayed} for that.
     */
    public static int getRegenDelayTicks(@NotNull PlayerEntity player) {
        return NaturalRegeneration.remainingDelay(player);
    }

    /**
     * Whether natural regeneration is held back right now: a delay is running and no
     * effect in {@code #florafare:ignores_regen_delay} overrides it.
     */
    public static boolean isRegenDelayed(@NotNull PlayerEntity player) {
        return NaturalRegeneration.isDelayed(player);
    }

    /** Ends the player's regeneration delay at once. */
    public static void clearRegenDelay(@NotNull PlayerEntity player) {
        NaturalRegeneration.setDelay(player, 0);
    }

    /**
     * Sets the player's regeneration delay to exactly {@code ticks} from now, replacing
     * whatever was running, shorter or longer. 0 or less clears it.
     */
    public static void setRegenDelay(@NotNull PlayerEntity player, int ticks) {
        NaturalRegeneration.setDelay(player, ticks);
    }

    /**
     * Makes the player's regeneration delay last at least {@code ticks} from now — what a
     * hit does. A longer delay already running is kept.
     */
    public static void extendRegenDelay(@NotNull PlayerEntity player, int ticks) {
        NaturalRegeneration.extendDelay(player, ticks);
    }

    private static PlayerFoodComponent component(@NotNull PlayerEntity player) {
        return ((IFoodComponentProvider) player).florafare$getFoodComponent();
    }
}
