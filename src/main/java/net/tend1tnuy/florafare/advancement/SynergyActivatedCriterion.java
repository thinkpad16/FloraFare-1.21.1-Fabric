package net.tend1tnuy.florafare.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.predicate.entity.EntityPredicate;
import net.minecraft.predicate.entity.LootContextPredicate;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Optional;

/**
 * {@code florafare:synergy_activated} — fires when a synergy's requirements are all
 * satisfied at once and it starts running on the player.
 *
 * <pre>{@code
 * "criteria": {
 *   "full_table": {
 *     "trigger": "florafare:synergy_activated",
 *     "conditions": { "synergy": "hearty_lunch" }
 *   }
 * }
 * }</pre>
 *
 * <p>{@code synergy} is optional and is the synergy's {@code id} exactly as the
 * {@code food_synergies} file spells it; leaving it out matches any synergy. Note this
 * is the activation, not the discovery — it fires every time the combination comes
 * together, including the first.
 */
public class SynergyActivatedCriterion extends AbstractCriterion<SynergyActivatedCriterion.Conditions> {

    @Override
    public Codec<Conditions> getConditionsCodec() {
        return Conditions.CODEC;
    }

    /** Called from {@link FlorafareCriteria}; server-side only. */
    public void trigger(ServerPlayerEntity player, String synergyId) {
        trigger(player, conditions -> conditions.matches(synergyId));
    }

    public record Conditions(Optional<LootContextPredicate> player, Optional<String> synergy)
            implements AbstractCriterion.Conditions {

        public static final Codec<Conditions> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.LOOT_CONTEXT_PREDICATE_CODEC.optionalFieldOf("player")
                        .forGetter(Conditions::player),
                Codec.STRING.optionalFieldOf("synergy").forGetter(Conditions::synergy)
        ).apply(instance, Conditions::new));

        public boolean matches(String synergyId) {
            if (synergy.isEmpty()) return true;
            return synergyId != null && synergy.get().equals(synergyId);
        }
    }
}
