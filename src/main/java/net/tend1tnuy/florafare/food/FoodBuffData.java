package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record FoodBuffData(
        String target,
        int duration,
        int nutrition,
        float saturation,
        double healthBonus,
        List<EffectData> effects,
        List<AttributeData> attributes,
        int priority,
        boolean alwaysEdible
) {
    private static final String KEY_TARGET      = "target";
    private static final String KEY_DURATION    = "duration";
    private static final String KEY_NUTRITION   = "nutrition";
    private static final String KEY_SATURATION  = "saturation";
    private static final String KEY_HEALTH_BONUS = "healthBonus";
    private static final String KEY_EFFECTS     = "effects";
    private static final String KEY_ATTRIBUTES  = "attributes";
    private static final String KEY_PRIORITY    = "priority";
    private static final String KEY_ALWAYS_EDIBLE = "alwaysEdible";

    public record EffectData(Identifier id, int duration, int amplifier) {}
    public record AttributeData(Identifier attributeId, double amount, String operation) {}

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_TARGET,       target);
        nbt.putInt(KEY_DURATION,        duration);
        nbt.putInt(KEY_NUTRITION,       nutrition);
        nbt.putFloat(KEY_SATURATION,    saturation);
        nbt.putDouble(KEY_HEALTH_BONUS, healthBonus);
        nbt.putInt(KEY_PRIORITY,        priority);
        nbt.putBoolean(KEY_ALWAYS_EDIBLE, alwaysEdible);
        // Delegate list serialization to the shared helper (#23)
        nbt.put(KEY_EFFECTS,    FoodNbtHelper.effectsToNbt(effects));
        nbt.put(KEY_ATTRIBUTES, FoodNbtHelper.attributesToNbt(attributes));
        return nbt;
    }

    /** Starts a fluent builder for a buff targeting the given id (item id or "#tag"). */
    public static Builder builder(String target) {
        return new Builder(target);
    }

    /**
     * Ergonomic alternative to the 9-argument record constructor for mods constructing
     * a {@link FoodBuffData} in code (e.g. to pass into {@code FlorafareAPI.registerFoodBuff}
     * or {@code FlorafareAPI.applyBuffToPlayer}). Every field defaults to the same value
     * the datapack loader uses when a JSON entry omits it.
     */
    public static final class Builder {
        private final String target;
        private int duration = 6000;
        private int nutrition = 0;
        private float saturation = 0.0f;
        private double healthBonus = 0.0;
        private final List<EffectData> effects = new ArrayList<>();
        private final List<AttributeData> attributes = new ArrayList<>();
        private int priority = 0;
        private boolean alwaysEdible = false;

        private Builder(String target) {
            this.target = target;
        }

        public Builder duration(int duration) { this.duration = duration; return this; }
        public Builder nutrition(int nutrition) { this.nutrition = nutrition; return this; }
        public Builder saturation(float saturation) { this.saturation = saturation; return this; }
        public Builder healthBonus(double healthBonus) { this.healthBonus = healthBonus; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder alwaysEdible(boolean alwaysEdible) { this.alwaysEdible = alwaysEdible; return this; }

        public Builder addEffect(Identifier effectId, int duration, int amplifier) {
            this.effects.add(new EffectData(effectId, duration, amplifier));
            return this;
        }

        public Builder addAttribute(Identifier attributeId, double amount, String operation) {
            this.attributes.add(new AttributeData(attributeId, amount, operation));
            return this;
        }

        public FoodBuffData build() {
            return new FoodBuffData(target, duration, nutrition, saturation, healthBonus,
                    new ArrayList<>(effects), new ArrayList<>(attributes), priority, alwaysEdible);
        }
    }

    public static FoodBuffData fromNbt(NbtCompound nbt) {
        int priority = nbt.contains(KEY_PRIORITY) ? nbt.getInt(KEY_PRIORITY) : 0;
        boolean alwaysEdible = nbt.contains(KEY_ALWAYS_EDIBLE) && nbt.getBoolean(KEY_ALWAYS_EDIBLE);
        return new FoodBuffData(
                nbt.getString(KEY_TARGET),
                nbt.getInt(KEY_DURATION),
                nbt.getInt(KEY_NUTRITION),
                nbt.getFloat(KEY_SATURATION),
                nbt.getDouble(KEY_HEALTH_BONUS),
                FoodNbtHelper.effectsFromNbt(nbt, KEY_EFFECTS),
                FoodNbtHelper.attributesFromNbt(nbt, KEY_ATTRIBUTES),
                priority,
                alwaysEdible
        );
    }
}
