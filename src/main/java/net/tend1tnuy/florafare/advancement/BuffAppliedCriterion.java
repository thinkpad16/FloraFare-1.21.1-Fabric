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
 * {@code florafare:buff_applied} — fires every time a food buff is granted or refreshed,
 * which is the closest thing to "the player ate a dish Florafare manages".
 *
 * <pre>{@code
 * "criteria": {
 *   "ate_any_meat": {
 *     "trigger": "florafare:buff_applied",
 *     "conditions": { "target": "#florafare:meats" }
 *   }
 * }
 * }</pre>
 *
 * <p>Two optional filters, and they are not the same thing:
 * <ul>
 *   <li>{@code food} — the item actually eaten ({@code "minecraft:cooked_beef"}).</li>
 *   <li>{@code target} — the datapack entry the buff came from, which for a tag entry is
 *       the tag itself ({@code "#florafare:meats"}). This is how an advancement says
 *       "any meat" without listing every meat in the game.</li>
 * </ul>
 * Both are normalized the way datapack targets are, so a missing {@code minecraft:}
 * namespace is filled in. With neither set, any buff matches.
 */
public class BuffAppliedCriterion extends AbstractCriterion<BuffAppliedCriterion.Conditions> {

    @Override
    public Codec<Conditions> getConditionsCodec() {
        return Conditions.CODEC;
    }

    /**
     * Called from {@link FlorafareCriteria}; server-side only.
     *
     * @param foodId the id of the item that was eaten
     * @param target the config target the buff resolved from
     */
    public void trigger(ServerPlayerEntity player, String foodId, String target) {
        trigger(player, conditions -> conditions.matches(foodId, target));
    }

    public record Conditions(Optional<LootContextPredicate> player,
                             Optional<String> food,
                             Optional<String> target)
            implements AbstractCriterion.Conditions {

        public static final Codec<Conditions> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.LOOT_CONTEXT_PREDICATE_CODEC.optionalFieldOf("player")
                        .forGetter(Conditions::player),
                Codec.STRING.optionalFieldOf("food").forGetter(Conditions::food),
                Codec.STRING.optionalFieldOf("target").forGetter(Conditions::target)
        ).apply(instance, Conditions::new));

        public boolean matches(String foodId, String buffTarget) {
            return matchesOne(food, foodId) && matchesOne(target, buffTarget);
        }

        private static boolean matchesOne(Optional<String> expected, String actual) {
            if (expected.isEmpty()) return true;
            if (actual == null) return false;
            return FoodBuffManager.normalizeTarget(expected.get()).equals(actual);
        }
    }
}
