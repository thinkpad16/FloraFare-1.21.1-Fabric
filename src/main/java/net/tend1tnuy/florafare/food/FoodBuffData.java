package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

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
