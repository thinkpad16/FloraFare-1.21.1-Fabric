package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

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
    private static final String KEY_ID           = "id";
    private static final String KEY_DURATION     = "duration";
    private static final String KEY_HEALTH_BONUS = "healthBonus";
    private static final String KEY_REQUIREMENTS = "requirements";
    private static final String KEY_EFFECTS      = "effects";
    private static final String KEY_ATTRIBUTES   = "attributes";

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_ID,            id);
        nbt.putInt(KEY_DURATION,         duration);
        nbt.putDouble(KEY_HEALTH_BONUS,  healthBonus);

        NbtList reqList = new NbtList();
        for (String req : requirements) reqList.add(net.minecraft.nbt.NbtString.of(req));
        nbt.put(KEY_REQUIREMENTS, reqList);

        // Delegate list serialization to the shared helper (#23)
        nbt.put(KEY_EFFECTS,    FoodNbtHelper.effectsToNbt(effects));
        nbt.put(KEY_ATTRIBUTES, FoodNbtHelper.attributesToNbt(attributes));

        return nbt;
    }

    public static FoodSynergyData fromNbt(NbtCompound nbt) {
        List<String> requirements = new ArrayList<>();
        if (nbt.contains(KEY_REQUIREMENTS, NbtElement.LIST_TYPE)) {
            NbtList reqList = nbt.getList(KEY_REQUIREMENTS, NbtElement.STRING_TYPE);
            for (int i = 0; i < reqList.size(); i++) requirements.add(reqList.getString(i));
        }

        return new FoodSynergyData(
                nbt.getString(KEY_ID),
                requirements,
                nbt.getInt(KEY_DURATION),
                nbt.getDouble(KEY_HEALTH_BONUS),
                FoodNbtHelper.effectsFromNbt(nbt, KEY_EFFECTS),
                FoodNbtHelper.attributesFromNbt(nbt, KEY_ATTRIBUTES)
        );
    }
}
