package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Data record representing the configuration for a food buff.
 */
public record FoodBuffData(
        String target,
        int duration,
        int nutrition,
        float saturation,
        double healthBonus,
        List<EffectData> effects,
        List<AttributeData> attributes
) {
    // NBT Keys for serialization
    private static final String KEY_TARGET = "target";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_NUTRITION = "nutrition";
    private static final String KEY_SATURATION = "saturation";
    private static final String KEY_HEALTH_BONUS = "healthBonus";
    private static final String KEY_EFFECTS = "effects";
    private static final String KEY_ATTRIBUTES = "attributes";
    private static final String KEY_ID = "id";
    private static final String KEY_AMOUNT = "amount";
    private static final String KEY_OPERATION = "op";
    private static final String KEY_AMPLIFIER = "amplifier";

    public record EffectData(Identifier id, int duration, int amplifier) {}
    public record AttributeData(Identifier attributeId, double amount, String operation) {}

    /**
     * Serializes this data to an NbtCompound.
     *
     * @return the serialized NBT data.
     */
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_TARGET, target);
        nbt.putInt(KEY_DURATION, duration);
        nbt.putInt(KEY_NUTRITION, nutrition);
        nbt.putFloat(KEY_SATURATION, saturation);
        nbt.putDouble(KEY_HEALTH_BONUS, healthBonus);

        NbtList effList = new NbtList();
        for (EffectData eff : effects) {
            NbtCompound effTag = new NbtCompound();
            effTag.putString(KEY_ID, eff.id().toString());
            effTag.putInt(KEY_DURATION, eff.duration());
            effTag.putInt(KEY_AMPLIFIER, eff.amplifier());
            effList.add(effTag);
        }
        nbt.put(KEY_EFFECTS, effList);

        NbtList attrList = new NbtList();
        for (AttributeData attr : attributes) {
            NbtCompound attrTag = new NbtCompound();
            attrTag.putString(KEY_ID, attr.attributeId().toString());
            attrTag.putDouble(KEY_AMOUNT, attr.amount());
            attrTag.putString(KEY_OPERATION, attr.operation());
            attrList.add(attrTag);
        }
        nbt.put(KEY_ATTRIBUTES, attrList);

        return nbt;
    }

    /**
     * Deserializes data from an NbtCompound.
     *
     * @param nbt The NBT tag to read from.
     * @return A new FoodBuffData instance.
     */
    public static FoodBuffData fromNbt(NbtCompound nbt) {
        List<EffectData> effects = new ArrayList<>();
        if (nbt.contains(KEY_EFFECTS, NbtElement.LIST_TYPE)) {
            NbtList effList = nbt.getList(KEY_EFFECTS, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < effList.size(); i++) {
                NbtCompound tag = effList.getCompound(i);
                effects.add(new EffectData(
                        Identifier.of(tag.getString(KEY_ID)),
                        tag.getInt(KEY_DURATION),
                        tag.getInt(KEY_AMPLIFIER)
                ));
            }
        }

        List<AttributeData> attrs = new ArrayList<>();
        if (nbt.contains(KEY_ATTRIBUTES, NbtElement.LIST_TYPE)) {
            NbtList attrList = nbt.getList(KEY_ATTRIBUTES, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < attrList.size(); i++) {
                NbtCompound tag = attrList.getCompound(i);
                attrs.add(new AttributeData(
                        Identifier.of(tag.getString(KEY_ID)),
                        tag.getDouble(KEY_AMOUNT),
                        tag.getString(KEY_OPERATION)
                ));
            }
        }

        return new FoodBuffData(
                nbt.getString(KEY_TARGET),
                nbt.getInt(KEY_DURATION),
                nbt.getInt(KEY_NUTRITION),
                nbt.getFloat(KEY_SATURATION),
                nbt.getDouble(KEY_HEALTH_BONUS),
                effects,
                attrs
        );
    }
}