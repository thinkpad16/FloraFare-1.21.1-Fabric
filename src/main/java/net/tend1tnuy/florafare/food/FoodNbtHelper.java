package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared NBT serialization helpers for {@link FoodBuffData.EffectData} and
 * {@link FoodBuffData.AttributeData} lists.
 *
 * Both {@link FoodBuffData} and {@link FoodSynergyData} previously duplicated
 * identical NBT read/write logic for these two list types (#23). Centralizing
 * them here eliminates that duplication and makes future changes to the on-disk
 * format a single-point edit.
 */
public final class FoodNbtHelper {

    private FoodNbtHelper() {}

    // -------------------------------------------------------------------------
    // EFFECT LIST
    // -------------------------------------------------------------------------

    public static NbtList effectsToNbt(List<FoodBuffData.EffectData> effects) {
        NbtList list = new NbtList();
        for (FoodBuffData.EffectData eff : effects) {
            NbtCompound tag = new NbtCompound();
            tag.putString("id",        eff.id().toString());
            tag.putInt("duration",     eff.duration());
            tag.putInt("amplifier",    eff.amplifier());
            list.add(tag);
        }
        return list;
    }

    public static List<FoodBuffData.EffectData> effectsFromNbt(NbtCompound nbt, String key) {
        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (!nbt.contains(key, NbtElement.LIST_TYPE)) return effects;
        NbtList list = nbt.getList(key, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound tag = list.getCompound(i);
            Identifier effId = Identifier.tryParse(tag.getString("id"));
            if (effId != null) {
                effects.add(new FoodBuffData.EffectData(
                        effId,
                        tag.getInt("duration"),
                        tag.getInt("amplifier")));
            }
        }
        return effects;
    }

    // -------------------------------------------------------------------------
    // ATTRIBUTE LIST
    // -------------------------------------------------------------------------

    public static NbtList attributesToNbt(List<FoodBuffData.AttributeData> attributes) {
        NbtList list = new NbtList();
        for (FoodBuffData.AttributeData attr : attributes) {
            NbtCompound tag = new NbtCompound();
            tag.putString("id",     attr.attributeId().toString());
            tag.putDouble("amount", attr.amount());
            tag.putString("op",     attr.operation());
            list.add(tag);
        }
        return list;
    }

    public static List<FoodBuffData.AttributeData> attributesFromNbt(NbtCompound nbt, String key) {
        List<FoodBuffData.AttributeData> attrs = new ArrayList<>();
        if (!nbt.contains(key, NbtElement.LIST_TYPE)) return attrs;
        NbtList list = nbt.getList(key, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound tag = list.getCompound(i);
            Identifier attrId = Identifier.tryParse(tag.getString("id"));
            if (attrId != null) {
                attrs.add(new FoodBuffData.AttributeData(
                        attrId,
                        tag.getDouble("amount"),
                        tag.getString("op")));
            }
        }
        return attrs;
    }
}
