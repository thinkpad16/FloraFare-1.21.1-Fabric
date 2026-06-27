package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record FoodSynergyData(
        String id,
        List<String> requirements,
        int duration,
        double healthBonus,
        List<FoodBuffData.EffectData> effects,
        List<FoodBuffData.AttributeData> attributes
) {
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        nbt.putInt("duration", duration);
        nbt.putDouble("healthBonus", healthBonus);

        NbtList reqList = new NbtList();
        for (String req : requirements) {
            reqList.add(net.minecraft.nbt.NbtString.of(req));
        }
        nbt.put("requirements", reqList);

        NbtList effList = new NbtList();
        for (var eff : effects) {
            NbtCompound effTag = new NbtCompound();
            effTag.putString("id", eff.id().toString());
            effTag.putInt("duration", eff.duration());
            effTag.putInt("amplifier", eff.amplifier());
            effList.add(effTag);
        }
        nbt.put("effects", effList);

        NbtList attrList = new NbtList();
        for (var attr : attributes) {
            NbtCompound attrTag = new NbtCompound();
            attrTag.putString("id", attr.attributeId().toString());
            attrTag.putDouble("amount", attr.amount());
            attrTag.putString("op", attr.operation());
            attrList.add(attrTag);
        }
        nbt.put("attributes", attrList);

        return nbt;
    }

    public static FoodSynergyData fromNbt(NbtCompound nbt) {
        List<String> requirements = new ArrayList<>();
        if (nbt.contains("requirements", NbtElement.LIST_TYPE)) {
            NbtList reqList = nbt.getList("requirements", NbtElement.STRING_TYPE);
            for (int i = 0; i < reqList.size(); i++) {
                requirements.add(reqList.getString(i));
            }
        }

        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (nbt.contains("effects", NbtElement.LIST_TYPE)) {
            NbtList effList = nbt.getList("effects", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < effList.size(); i++) {
                NbtCompound tag = effList.getCompound(i);
                Identifier effId = Identifier.tryParse(tag.getString("id"));
                if (effId != null) {
                    effects.add(new FoodBuffData.EffectData(
                            effId,
                            tag.getInt("duration"),
                            tag.getInt("amplifier")
                    ));
                }
            }
        }

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (nbt.contains("attributes", NbtElement.LIST_TYPE)) {
            NbtList attrList = nbt.getList("attributes", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < attrList.size(); i++) {
                NbtCompound tag = attrList.getCompound(i);
                Identifier attrId = Identifier.tryParse(tag.getString("id"));
                if (attrId != null) {
                    attributes.add(new FoodBuffData.AttributeData(
                            attrId,
                            tag.getDouble("amount"),
                            tag.getString("op")
                    ));
                }
            }
        }

        return new FoodSynergyData(
                nbt.getString("id"),
                requirements,
                nbt.getInt("duration"),
                nbt.getDouble("healthBonus"),
                effects,
                attributes
        );
    }
}