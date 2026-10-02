package net.tend1tnuy.florafare.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodSynergyData;

/**
 * Observation hooks for other mods to react to Florafare's food/buff/synergy state
 * changes without touching internal classes.
 *
 * <p>Every event here is fired on the logical server only, matching where Florafare's
 * own buff/synergy state lives. All but two are informational: they fire from the same
 * call sites Florafare itself uses internally, so listening to them never changes
 * existing behavior.
 *
 * <p>The first exception is {@link #BUFF_APPLYING}, which runs <em>before</em> a buff is
 * granted and can veto it. It exists because the alternatives were both bad: a mod that
 * wanted to suppress Florafare's buff for one particular situation — a status effect the
 * player has, a dimension, a difficulty setting — could previously only reach for
 * {@link FlorafareAPI#excludeFood(String)}, which is permanent and global, or let the
 * buff apply and immediately remove it, which fires a spurious
 * {@link #BUFF_APPLIED}/{@link #BUFF_REMOVED} pair at every listener.
 *
 * <p>{@link #REGEN_DELAY} is the other one that decides rather than observes: it sets how
 * long natural regeneration waits after a player is hurt.
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
     * manage, so its datapack nutrition still applies, its vanilla effects stay
     * suppressed and the entry's own {@code effects} are granted in their place — the
     * player just cannot carry another buff, so its attributes do not apply.
     *
     * <p>Also asked for a hunger-only entry ({@code duration <= 0}), which grants no buff
     * but does take the bite over; vetoing it hands the bite back to vanilla the same way.
     * No other buff event fires for such an entry.
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

    /**
     * Fired after an active buff is removed from a player (expiry, Forgotten Mead, a clear
     * command, or death).
     *
     * <p>Fired once the removal is complete, never from inside it, so a listener may call
     * back into {@link FlorafareAPI} — remove another buff, clear the lot — safely.
     */
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

    /**
     * Fired when a player takes damage, to decide how long their natural (hunger)
     * regeneration then waits, in ticks.
     *
     * <p>Fired for every hit on a server player that actually lands: more than 0 damage
     * and not blocked by a shield. {@code proposedTicks} is what Florafare worked out —
     * {@code regenDelayAfterDamage x florafare:regen_delay_multiplier}, rounded to ticks,
     * and 0 when the delay is turned off, the attribute is 0, or the damage type is in
     * {@code #florafare:no_regen_delay}. Listeners run in registration order, each handed
     * the previous one's answer, and the last answer is used: return the value unchanged
     * to stay out of it, 0 (or less) for no delay from this hit, or any other number of
     * ticks. Because a 0 is still handed on, a listener can impose a delay even where
     * Florafare's config would not.
     *
     * <p>A positive result starts the delay, or extends one already running to at least
     * that many ticks from now; it never shortens a longer one (use
     * {@link FlorafareAPI#setRegenDelay} or {@link FlorafareAPI#clearRegenDelay} for that).
     * While the player has an effect in {@code #florafare:ignores_regen_delay} the delay
     * runs on but does not hold regeneration back. Only hunger regeneration ever waits;
     * potions, the Regeneration effect and other healing are never touched.
     */
    public static final Event<RegenDelay> REGEN_DELAY = EventFactory.createArrayBacked(
            RegenDelay.class,
            listeners -> (player, source, proposedTicks) -> {
                int ticks = proposedTicks;
                for (RegenDelay listener : listeners) {
                    ticks = listener.regenDelayTicks(player, source, ticks);
                }
                return ticks;
            }
    );

    @FunctionalInterface
    public interface RegenDelay {
        /**
         * @param player        the player who was hurt
         * @param source        the damage
         * @param proposedTicks the delay so far, in ticks: Florafare's, or the previous
         *                      listener's answer
         * @return the delay to use, in ticks; 0 or less for none
         */
        int regenDelayTicks(ServerPlayerEntity player, DamageSource source, int proposedTicks);
    }

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
