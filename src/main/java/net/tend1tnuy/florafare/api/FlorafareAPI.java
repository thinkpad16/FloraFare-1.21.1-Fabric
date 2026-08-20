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
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
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
    public static FoodBuffData getBuffData(ItemStack stack) {
        return FoodBuffManager.getConfig(stack);
    }

    /**
     * Checks if the given ItemStack has a unique buff applied via commands.
     *
     * @param stack The item to check.
     * @return True if the item contains a custom command-based buff identifier.
     */
    public static boolean hasCustomCommandBuff(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customData != null && customData.copyNbt().contains("FlorafareBuffId");
    }

    /**
     * Applies a food buff to the player based on the provided configuration.
     *
     * @param player The player receiving the buff.
     * @param stack  The consumed item.
     * @param data   The buff configuration data.
     */
    public static void applyBuffToPlayer(PlayerEntity player, ItemStack stack, FoodBuffData data) {
        if (data != null && !player.getWorld().isClient()) {
            ((IFoodComponentProvider) player).florafare$getFoodComponent().tryAddBuff(stack, data);
        }
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
    public static void excludeFood(String itemId) {
        FoodBuffManager.excludeItem(itemId);
    }

    /** Reverses a previous {@link #excludeFood(String)} call. */
    public static void includeFood(String itemId) {
        FoodBuffManager.includeItem(itemId);
    }

    /** Whether Florafare has been told to fully ignore this stack's item. */
    public static boolean isFoodExcluded(ItemStack stack) {
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
    public static void registerFoodBuff(String target, FoodBuffData data) {
        String normalized = FoodBuffManager.normalizeTarget(target);
        FoodBuffManager.putConfig(normalized, data);
    }

    /**
     * Registers a food synergy in code instead of via a datapack JSON file.
     * See {@link #registerFoodBuff(String, FoodBuffData)} for the same timing caveat.
     */
    public static void registerSynergy(FoodSynergyData data) {
        FoodSynergyManager.putSynergy(data);
    }

    /** The buffs currently active on this player. Read-only snapshot; mutate via the other API methods. */
    public static List<ActiveFoodBuff> getActiveBuffs(PlayerEntity player) {
        return Collections.unmodifiableList(component(player).getActiveBuffs());
    }

    /** The synergies currently active on this player. Read-only snapshot. */
    public static List<ActiveFoodBuff> getActiveSynergies(PlayerEntity player) {
        return Collections.unmodifiableList(component(player).getActiveSynergies());
    }

    /** Item ids this player has discovered (eaten at least once). Read-only snapshot. */
    public static Set<String> getDiscoveredFoods(PlayerEntity player) {
        return Collections.unmodifiableSet(component(player).getDiscoveredFoods());
    }

    /** Synergy ids this player has discovered (activated at least once). Read-only snapshot. */
    public static Set<String> getDiscoveredSynergies(PlayerEntity player) {
        return Collections.unmodifiableSet(component(player).getDiscoveredSynergies());
    }

    /** Removes every active buff and synergy from the player, same as {@code /florafare clear}. */
    public static void clearBuffs(PlayerEntity player) {
        component(player).clearAllBuffs();
    }

    /**
     * Marks an item as discovered for this player without granting its buff, unlocking
     * its journal entry and firing {@link FlorafareEvents#FOOD_DISCOVERED}.
     *
     * @return true if this item was newly discovered, false if it already was.
     */
    public static boolean unlockFood(PlayerEntity player, String itemId) {
        return component(player).unlockFood(itemId);
    }

    private static PlayerFoodComponent component(PlayerEntity player) {
        return ((IFoodComponentProvider) player).florafare$getFoodComponent();
    }
}