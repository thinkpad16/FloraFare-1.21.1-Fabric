package net.tend1tnuy.florafare.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodSynergyData;

/**
 * Observation hooks for other mods to react to Florafare's food/buff/synergy state
 * changes without touching internal classes.
 *
 * <p>Every event here is fired on the logical server only, matching where Florafare's
 * own buff/synergy state lives. All but one are informational: they fire from the same
 * call sites Florafare itself uses internally, so listening to them never changes
 * existing behavior.
 *
 * <p>The exception is {@link #BUFF_APPLYING}, which runs <em>before</em> a buff is
 * granted and can veto it. It exists because the alternatives were both bad: a mod that
 * wanted to suppress Florafare's buff for one particular situation — a status effect the
 * player has, a dimension, a difficulty setting — could previously only reach for
 * {@link FlorafareAPI#excludeFood(String)}, which is permanent and global, or let the
 * buff apply and immediately remove it, which fires a spurious
 * {@link #BUFF_APPLIED}/{@link #BUFF_REMOVED} pair at every listener.
 *
 * <p>For mods that want Florafare to keep its hands off one of their items entirely,
 * {@link FlorafareAPI#excludeFood(String)} is still the right call — it takes the item
 * out of tooltips, the journal and hunger prediction too, which a veto here does not.
 */
public final class FlorafareEvents {

    private FlorafareEvents() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Fired before a buff is granted or refreshed, and able to stop it.
     *
     * <p>Returning false from any listener cancels the buff: nothing is added to the
     * player's slots, no attributes or effects are applied, no synergy is re-evaluated,
     * and {@link #BUFF_APPLIED} does not fire. The food still counts as discovered — the
     * player did eat it.
     *
     * <p>A veto hands the bite back to vanilla <em>whole</em>. Florafare normally
     * replaces two parts of eating on the strength of the buff it grants — the food's
     * nutrition and saturation come from the datapack entry instead of the item, and the
     * food's own status effects are suppressed because the buff's effects stand in for
     * them. Refusing the buff refuses those too, so the player gets exactly the meal they
     * would have had with Florafare not installed. Everything else vanilla does when a
     * player eats (the sound, the stat, the stack decrement) was never touched to begin
     * with.
     *
     * <p>This is the one respect in which a veto differs from Florafare simply having no
     * free buff slot. Slots being full is not a veto: the food is still Florafare's to
     * manage, so its datapack nutrition still applies and its vanilla effects stay
     * suppressed — the player just cannot carry another buff.
     *
     * <p>Listeners run in registration order and the first veto wins; later listeners are
     * not consulted. Treat it as a filter, not as a notification — use
     * {@link #BUFF_APPLIED} for anything that should observe the outcome.
     *
     * @see FlorafareAPI#excludeFood(String) for taking an item away from Florafare entirely
     */
    public static final Event<BuffApplying> BUFF_APPLYING = EventFactory.createArrayBacked(
            BuffApplying.class,
            listeners -> (player, stack, data) -> {
                for (BuffApplying listener : listeners) {
                    if (!listener.allowBuff(player, stack, data)) return false;
                }
                return true;
            }
    );

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
    public interface BuffApplying {
        /**
         * @param data the config about to be applied — read-only; its lists are immutable
         * @return false to stop Florafare granting this buff
         */
        boolean allowBuff(PlayerEntity player, ItemStack stack, FoodBuffData data);
    }

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
