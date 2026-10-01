package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FoodSynergyManager {
    private static final Map<String, FoodSynergyData> SYNERGIES = new ConcurrentHashMap<>();

    /**
     * Synergies registered from code through
     * {@link net.tend1tnuy.florafare.api.FlorafareAPI#registerSynergy}, replayed after
     * every datapack reload. Without this they were dropped by the {@link #clear()}
     * below, which runs on world load — after every mod initializer has already run.
     */
    private static final Map<String, FoodSynergyData> API_SYNERGIES = new ConcurrentHashMap<>();

    /**
     * The file each current synergy was loaded from (resource id), or
     * {@link FoodBuffManager#API_SOURCE} for one registered from code.
     */
    private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();

    public static void clear() {
        SYNERGIES.clear();
        SOURCES.clear();
        SYNERGIES.putAll(API_SYNERGIES);
        for (String id : API_SYNERGIES.keySet()) SOURCES.put(id, FoodBuffManager.API_SOURCE);
    }

    /** Registers a synergy that survives datapack reloads. See {@link #API_SYNERGIES}. */
    public static void putApiSynergy(FoodSynergyData synergy) {
        API_SYNERGIES.put(synergy.id(), synergy);
        SYNERGIES.put(synergy.id(), synergy);
        SOURCES.put(synergy.id(), FoodBuffManager.API_SOURCE);
    }

    /** Where the synergy in force under {@code id} came from, or null if there is none. */
    public static String sourceOf(String id) {
        return id == null ? null : SOURCES.get(id);
    }

    public static NbtCompound serializeSynergies() {
        NbtCompound root = new NbtCompound();
        for (Map.Entry<String, FoodSynergyData> entry : SYNERGIES.entrySet()) {
            root.put(entry.getKey(), entry.getValue().toNbt());
        }
        return root;
    }

    /**
     * Replaces the client-side synergy map with data received from the server.
     * Mirrors {@link #serializeSynergies()}. Updates in place (put new entries,
     * then drop stale keys) instead of clear-then-put, so the integrated server
     * thread never observes a momentarily empty map in singleplayer.
     */
    public static void loadSynergiesFromNbt(NbtCompound root) {
        Map<String, FoodSynergyData> incoming = new HashMap<>();
        for (String key : root.getKeys()) {
            incoming.put(key, FoodSynergyData.fromNbt(root.getCompound(key)));
        }
        SYNERGIES.putAll(incoming);
        SYNERGIES.keySet().retainAll(incoming.keySet());
    }

    public static void putSynergy(FoodSynergyData synergy) {
        putSynergy(synergy, null);
    }

    /**
     * Registers a synergy loaded from {@code source}, replacing any synergy with the same
     * id — the later definition wins, which the listener makes deterministic by loading
     * files in pack order. The caller reports the replacement; this only does it.
     *
     * @return the synergy it replaced, or null
     */
    public static FoodSynergyData putSynergy(FoodSynergyData synergy, String source) {
        SOURCES.put(synergy.id(), source == null ? FoodBuffManager.API_SOURCE : source);
        return SYNERGIES.put(synergy.id(), synergy);
    }

    public static List<FoodSynergyData> getAllSynergies() {
        return new ArrayList<>(SYNERGIES.values());
    }

    public static FoodSynergyData getSynergy(String id) {
        return SYNERGIES.get(id);
    }
}