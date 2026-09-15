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
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
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
     * {@link FlorafareEvents#BUFF_APPLYING} vetoed it.
     *
     * @param player The player receiving the buff.
     * @param stack  The consumed item, used for the buff's identity and HUD icon.
     * @param data   The buff configuration data; null is accepted and does nothing.
     * @return true if the buff is now active on the player
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
     * @param itemId The item id to exclude, e.g. {@code "modid:custom_stew"}.
     */
    public static void excludeFood(@Nullable String itemId) {
        FoodBuffManager.excludeItem(itemId);
    }

    /** Reverses a previous {@link #excludeFood(String)} call. */
    public static void includeFood(@Nullable String itemId) {
        FoodBuffManager.includeItem(itemId);
    }

    /** Whether Florafare has been told to fully ignore this stack's item. */
    public static boolean isFoodExcluded(@NotNull ItemStack stack) {
        return FoodBuffManager.isExcluded(stack);
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
     * @return true if such a buff was active and has been removed
     */
    public static boolean removeBuff(@NotNull PlayerEntity player, @Nullable String targetOrItemId) {
        return component(player).removeBuff(targetOrItemId);
    }

    /** Removes every active buff and synergy from the player, same as {@code /florafare clear}. */
    public static void clearBuffs(@NotNull PlayerEntity player) {
        component(player).clearAllBuffs();
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

    private static PlayerFoodComponent component(@NotNull PlayerEntity player) {
        return ((IFoodComponentProvider) player).florafare$getFoodComponent();
    }
}
