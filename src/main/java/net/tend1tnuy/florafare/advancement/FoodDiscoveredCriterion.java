package net.tend1tnuy.florafare.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.predicate.entity.EntityPredicate;
import net.minecraft.predicate.entity.LootContextPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.Optional;

/**
 * {@code florafare:food_discovered} — fires the first time a player records a dish in
 * their journal, whether by eating it or through {@code /florafare journal unlock}.
 *
 * <pre>{@code
 * "criteria": {
 *   "tasted_bread": {
 *     "trigger": "florafare:food_discovered",
 *     "conditions": { "food": "minecraft:bread" }
 *   }
 * }
 * }</pre>
 *
 * <p>{@code food} is optional; leaving it out matches any discovery, which is what an
 * advancement counting "eat anything new" wants. The value is an item id or a
 * {@code potion:} target — the same string the journal stores — and is normalized the
 * way a datapack target is, so {@code "bread"} and {@code "minecraft:bread"} both work.
 */
public class FoodDiscoveredCriterion extends AbstractCriterion<FoodDiscoveredCriterion.Conditions> {

    @Override
    public Codec<Conditions> getConditionsCodec() {
        return Conditions.CODEC;
    }

    /** Called from {@link FlorafareCriteria}; server-side only. */
    public void trigger(ServerPlayerEntity player, String foodId) {
        trigger(player, conditions -> conditions.matches(foodId));
    }

    public record Conditions(Optional<LootContextPredicate> player, Optional<String> food)
            implements AbstractCriterion.Conditions {

        public static final Codec<Conditions> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.LOOT_CONTEXT_PREDICATE_CODEC.optionalFieldOf("player")
                        .forGetter(Conditions::player),
                Codec.STRING.optionalFieldOf("food").forGetter(Conditions::food)
        ).apply(instance, Conditions::new));

        public boolean matches(String foodId) {
            if (food.isEmpty()) return true;
            if (foodId == null) return false;
            return FoodBuffManager.normalizeTarget(food.get()).equals(foodId);
        }
    }
}
