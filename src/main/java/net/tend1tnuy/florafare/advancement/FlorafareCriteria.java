package net.tend1tnuy.florafare.advancement;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.api.FlorafareEvents;

/**
 * Florafare's advancement triggers, and the wiring that fires them.
 *
 * <p>Until these existed, the only way to react to a discovery or a synergy was to write
 * a Java mod against {@link FlorafareEvents}. A datapack — which is how most people
 * actually build progression — had nothing to hook into. These three triggers close
 * that gap: an advancement, a reward recipe or a loot table can now key off eating.
 *
 * <p>Deliberately implemented as listeners on the public events rather than as calls
 * spliced into {@code PlayerFoodComponent}: the events already fire at exactly the right
 * moments, on the logical server only, and going through them means the buff/discovery
 * code paths are untouched by this feature. If a listener here ever throws, it cannot
 * corrupt buff state — it runs after the state change is complete.
 */
public final class FlorafareCriteria {

    private FlorafareCriteria() {}

    public static final FoodDiscoveredCriterion    FOOD_DISCOVERED    = new FoodDiscoveredCriterion();
    public static final SynergyActivatedCriterion  SYNERGY_ACTIVATED  = new SynergyActivatedCriterion();
    public static final BuffAppliedCriterion       BUFF_APPLIED       = new BuffAppliedCriterion();

    /** Registers the triggers and hooks them to Florafare's own events. Call once, at init. */
    public static void register() {
        Registry.register(Registries.CRITERION, Florafare.id("food_discovered"),    FOOD_DISCOVERED);
        Registry.register(Registries.CRITERION, Florafare.id("synergy_activated"),  SYNERGY_ACTIVATED);
        Registry.register(Registries.CRITERION, Florafare.id("buff_applied"),       BUFF_APPLIED);

        // instanceof rather than a cast: the events are documented as server-side, but a
        // fake player from another mod is not a ServerPlayerEntity and has no advancement
        // tracker to trigger against.
        FlorafareEvents.FOOD_DISCOVERED.register((player, itemId) -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                FOOD_DISCOVERED.trigger(serverPlayer, itemId);
            }
        });

        FlorafareEvents.SYNERGY_ACTIVATED.register((player, synergy, buff) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && synergy != null) {
                SYNERGY_ACTIVATED.trigger(serverPlayer, synergy.id());
            }
        });

        FlorafareEvents.BUFF_APPLIED.register((player, stack, buff) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && buff != null) {
                BUFF_APPLIED.trigger(serverPlayer, buff.getConsumedItemId(), buff.getTarget());
            }
        });
    }
}
