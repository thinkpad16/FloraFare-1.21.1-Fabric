package net.tend1tnuy.florafare.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.food.FoodSynergyData;

/**
 * Observation hooks for other mods to react to Florafare's food/buff/synergy state
 * changes without touching internal classes.
 *
 * <p>All events here are informational (non-cancellable) and are only ever fired on
 * the logical server, matching where Florafare's own buff/synergy state lives. They
 * fire from the same call sites Florafare itself uses internally, so listening to
 * them never changes existing behavior.
 *
 * <p>For mods that need Florafare to fully ignore one of their items (so their own
 * food handling can run instead), see {@link FlorafareAPI#excludeFood(String)} rather
 * than trying to cancel one of these events.
 */
public final class FlorafareEvents {

    private FlorafareEvents() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Fired after a buff is newly granted or refreshed on a player. */
    public static final Event<BuffApplied> BUFF_APPLIED = EventFactory.createArrayBacked(
            BuffApplied.class,
            listeners -> (player, stack, buff) -> {
                for (BuffApplied listener : listeners) {
                    listener.onBuffApplied(player, stack, buff);
                }
            }
    );

    /** Fired after an active buff is removed from a player (expiry, Forgotten Mead, or a clear command). */
    public static final Event<BuffRemoved> BUFF_REMOVED = EventFactory.createArrayBacked(
            BuffRemoved.class,
            listeners -> (player, buff) -> {
                for (BuffRemoved listener : listeners) {
                    listener.onBuffRemoved(player, buff);
                }
            }
    );

    /** Fired when a synergy's requirements become satisfied and it activates for a player. */
    public static final Event<SynergyActivated> SYNERGY_ACTIVATED = EventFactory.createArrayBacked(
            SynergyActivated.class,
            listeners -> (player, synergy, buff) -> {
                for (SynergyActivated listener : listeners) {
                    listener.onSynergyActivated(player, synergy, buff);
                }
            }
    );

    /** Fired when an active synergy ends, whether by expiry or its requirements no longer being met. */
    public static final Event<SynergyEnded> SYNERGY_ENDED = EventFactory.createArrayBacked(
            SynergyEnded.class,
            listeners -> (player, buff) -> {
                for (SynergyEnded listener : listeners) {
                    listener.onSynergyEnded(player, buff);
                }
            }
    );

    /** Fired the first time a player discovers (eats) a given food item. */
    public static final Event<FoodDiscovered> FOOD_DISCOVERED = EventFactory.createArrayBacked(
            FoodDiscovered.class,
            listeners -> (player, itemId) -> {
                for (FoodDiscovered listener : listeners) {
                    listener.onFoodDiscovered(player, itemId);
                }
            }
    );

    @FunctionalInterface
    public interface BuffApplied {
        void onBuffApplied(PlayerEntity player, ItemStack stack, ActiveFoodBuff buff);
    }

    @FunctionalInterface
    public interface BuffRemoved {
        void onBuffRemoved(PlayerEntity player, ActiveFoodBuff buff);
    }

    @FunctionalInterface
    public interface SynergyActivated {
        void onSynergyActivated(PlayerEntity player, FoodSynergyData synergy, ActiveFoodBuff buff);
    }

    @FunctionalInterface
    public interface SynergyEnded {
        void onSynergyEnded(PlayerEntity player, ActiveFoodBuff buff);
    }

    @FunctionalInterface
    public interface FoodDiscovered {
        void onFoodDiscovered(PlayerEntity player, String itemId);
    }
}
