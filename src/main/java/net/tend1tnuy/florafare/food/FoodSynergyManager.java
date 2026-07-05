package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FoodSynergyManager {
    private static final Map<String, FoodSynergyData> SYNERGIES = new ConcurrentHashMap<>();

    public static void clear() {
        SYNERGIES.clear();
    }

    public static void putSynergy(FoodSynergyData synergy) {
        SYNERGIES.put(synergy.id(), synergy);
    }

    public static List<FoodSynergyData> getAllSynergies() {
        return new ArrayList<>(SYNERGIES.values());
    }

    public static FoodSynergyData getSynergy(String id) {
        return SYNERGIES.get(id);
    }

    public static NbtCompound serializeSynergies() {
        NbtCompound root = new NbtCompound();
        for (Map.Entry<String, FoodSynergyData> entry : SYNERGIES.entrySet()) {
            root.put(entry.getKey(), entry.getValue().toNbt());
        }
        return root;
    }

    public static void loadSynergiesFromNbt(NbtCompound root) {
        Map<String, FoodSynergyData> incoming = new HashMap<>();
        for (String key : root.getKeys()) {
            incoming.put(key, FoodSynergyData.fromNbt(root.getCompound(key)));
        }
        SYNERGIES.putAll(incoming);
        SYNERGIES.keySet().retainAll(incoming.keySet());
    }
}