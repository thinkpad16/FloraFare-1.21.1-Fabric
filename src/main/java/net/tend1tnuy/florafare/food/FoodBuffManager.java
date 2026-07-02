package net.tend1tnuy.florafare.food;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.item.ForgottenMeadItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for food buff configurations.
 * Handles datapack-loaded configs, runtime-generated command buffs, and fallback logic.
 */
public class FoodBuffManager {
    private static final Map<String, FoodBuffData> CONFIGS = new ConcurrentHashMap<>();
    private static final Map<String, FoodBuffData> RUNTIME_STACK_CONFIGS = new ConcurrentHashMap<>();

    private static final String RUNTIME_FILE_NAME = "florafare_runtime.dat";
    private static final String BUFF_ID_KEY = "FlorafareBuffId";

    public static int AUTO_GEN_DURATION_MULT = 1200;
    public static double AUTO_GEN_HEALTH_MULT = 0.5;

    public static void clear() {
        CONFIGS.clear();
    }

    public static void putConfig(String target, FoodBuffData data) {
        if (CONFIGS.containsKey(target)) {
            FoodBuffData existing = CONFIGS.get(target);
            if (data.priority() >= existing.priority()) {
                CONFIGS.put(target, data);
            }
        } else {
            CONFIGS.put(target, data);
        }
    }

    public static void setRuntimeStackConfig(String uuid, FoodBuffData data) {
        RUNTIME_STACK_CONFIGS.put(uuid, data);
    }

    public static void saveRuntimeConfigs() {
        NbtCompound root = new NbtCompound();
        for (Map.Entry<String, FoodBuffData> entry : RUNTIME_STACK_CONFIGS.entrySet()) {
            root.put(entry.getKey(), entry.getValue().toNbt());
        }

        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(RUNTIME_FILE_NAME);
            NbtIo.writeCompressed(root, path);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save Florafare runtime buffs!", e);
        }
    }

    public static void loadRuntimeConfigs() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(RUNTIME_FILE_NAME);
        if (!Files.exists(path)) return;

        try {
            NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
            RUNTIME_STACK_CONFIGS.clear();

            for (String key : root.getKeys()) {
                FoodBuffData data = FoodBuffData.fromNbt(root.getCompound(key));
                RUNTIME_STACK_CONFIGS.put(key, data);
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to load Florafare runtime buffs!", e);
        }
    }

    public static FoodBuffData getConfig(ItemStack stack) {
        if (stack.getItem() instanceof ForgottenMeadItem) {
            return null;
        }

        NbtComponent customDataComp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customDataComp != null) {
            NbtCompound nbt = customDataComp.copyNbt();
            if (nbt.contains(BUFF_ID_KEY)) {
                String uuid = nbt.getString(BUFF_ID_KEY);
                if (RUNTIME_STACK_CONFIGS.containsKey(uuid)) {
                    return RUNTIME_STACK_CONFIGS.get(uuid);
                }
            }
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());

        String itemTarget = "item:" + itemId.toString();
        if (CONFIGS.containsKey(itemTarget)) return CONFIGS.get(itemTarget);

        for (TagKey<?> tag : stack.streamTags().toList()) {
            String tagTarget = "tag:" + tag.id().toString();
            if (CONFIGS.containsKey(tagTarget)) return CONFIGS.get(tagTarget);
        }

        String nsTarget = "namespace:" + itemId.getNamespace();
        if (CONFIGS.containsKey(nsTarget)) return CONFIGS.get(nsTarget);

        if (CONFIGS.containsKey("template:default")) return CONFIGS.get("template:default");

        FoodComponent foodComponent = stack.get(DataComponentTypes.FOOD);
        if (foodComponent != null) {
            return generateFromVanilla(foodComponent, itemId);
        }

        return null;
    }

    private static FoodBuffData generateFromVanilla(FoodComponent food, Identifier itemId) {
        int durationTicks = food.nutrition() * AUTO_GEN_DURATION_MULT;
        double healthBonus = food.nutrition() * AUTO_GEN_HEALTH_MULT;

        return new FoodBuffData(
                "item:" + itemId.toString(),
                durationTicks,
                food.nutrition(),
                food.saturation(),
                healthBonus,
                new ArrayList<>(),
                new ArrayList<>(),
                0
        );
    }

    public static int getConfigCount() {
        return CONFIGS.size();
    }

    public static List<FoodBuffData> getAllConfigs() {
        return new ArrayList<>(CONFIGS.values());
    }

    /**
     * Serializes the entire resolved config map (keyed by target string) into NBT
     * for server-to-client synchronization.
     */
    public static NbtCompound serializeConfigs() {
        NbtCompound root = new NbtCompound();
        for (Map.Entry<String, FoodBuffData> entry : CONFIGS.entrySet()) {
            root.put(entry.getKey(), entry.getValue().toNbt());
        }
        return root;
    }

    /**
     * Replaces the client-side config map with data received from the server.
     * Mirrors {@link #serializeConfigs()} so that {@link #getConfig(ItemStack)}
     * resolves identically on the client.
     *
     * Updates in place (put new entries, then drop stale keys) instead of
     * clear-then-put, so the integrated server thread never observes a
     * momentarily empty map in singleplayer.
     */
    public static void loadConfigsFromNbt(NbtCompound root) {
        Map<String, FoodBuffData> incoming = new HashMap<>();
        for (String key : root.getKeys()) {
            incoming.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
        }
        CONFIGS.putAll(incoming);
        CONFIGS.keySet().retainAll(incoming.keySet());
    }
}