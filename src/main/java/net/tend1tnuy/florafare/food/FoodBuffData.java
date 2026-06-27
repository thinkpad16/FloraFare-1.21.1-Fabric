package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
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
        int priority
) {
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
    private static final String KEY_PRIORITY = "priority";

    public record EffectData(Identifier id, int duration, int amplifier) {}
    public record AttributeData(Identifier attributeId, double amount, String operation) {}

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_TARGET, target);
        nbt.putInt(KEY_DURATION, duration);
        nbt.putInt(KEY_NUTRITION, nutrition);
        nbt.putFloat(KEY_SATURATION, saturation);
        nbt.putDouble(KEY_HEALTH_BONUS, healthBonus);
        nbt.putInt(KEY_PRIORITY, priority);

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

    public static FoodBuffData fromNbt(NbtCompound nbt) {
        List<EffectData> effects = new ArrayList<>();
        if (nbt.contains(KEY_EFFECTS, NbtElement.LIST_TYPE)) {
            NbtList effList = nbt.getList(KEY_EFFECTS, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < effList.size(); i++) {
                NbtCompound tag = effList.getCompound(i);
                Identifier effId = Identifier.tryParse(tag.getString(KEY_ID));
                if (effId != null) {
                    effects.add(new EffectData(
                            effId,
                            tag.getInt(KEY_DURATION),
                            tag.getInt(KEY_AMPLIFIER)
                    ));
                }
            }
        }

        List<AttributeData> attrs = new ArrayList<>();
        if (nbt.contains(KEY_ATTRIBUTES, NbtElement.LIST_TYPE)) {
            NbtList attrList = nbt.getList(KEY_ATTRIBUTES, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < attrList.size(); i++) {
                NbtCompound tag = attrList.getCompound(i);
                Identifier attrId = Identifier.tryParse(tag.getString(KEY_ID));
                if (attrId != null) {
                    attrs.add(new AttributeData(
                            attrId,
                            tag.getDouble(KEY_AMOUNT),
                            tag.getString(KEY_OPERATION)
                    ));
                }
            }
        }

        int priority = nbt.contains(KEY_PRIORITY) ? nbt.getInt(KEY_PRIORITY) : 0;

        return new FoodBuffData(
                nbt.getString(KEY_TARGET),
                nbt.getInt(KEY_DURATION),
                nbt.getInt(KEY_NUTRITION),
                nbt.getFloat(KEY_SATURATION),
                nbt.getDouble(KEY_HEALTH_BONUS),
                effects,
                attrs,
                priority
        );
    }
}