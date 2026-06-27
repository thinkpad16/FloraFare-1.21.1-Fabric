package net.tend1tnuy.florafare.food;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FoodSynergyManager {
    private static final Map<String, FoodSynergyData> SYNERGIES = new HashMap<>();

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
}