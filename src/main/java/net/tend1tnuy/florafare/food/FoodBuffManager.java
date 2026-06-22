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

/**
 * Manager for food buff configurations, handling both datapack-loaded configs
 * and runtime-generated command buffs.
 */
public class FoodBuffManager {

    private static final Map<String, FoodBuffData> CONFIGS = new HashMap<>();
    private static final Map<String, FoodBuffData> RUNTIME_STACK_CONFIGS = new HashMap<>();
    private static final String RUNTIME_FILE_NAME = "florafare_runtime.dat";
    private static final String BUFF_ID_KEY = "FlorafareBuffId";

    // Global multipliers for auto-generation
    public static int AUTO_GEN_DURATION_MULT = 1200;
    public static double AUTO_GEN_HEALTH_MULT = 0.5;

    /**
     * Clears all datapack-loaded configurations.
     */
    public static void clear() {
        CONFIGS.clear();
    }

    /**
     * Adds a configuration to the manager.
     */
    public static void putConfig(String target, FoodBuffData data) {
        if (CONFIGS.containsKey(target)) {
            Florafare.LOGGER.warn("Warning: Buff for '{}' was overwritten by a new datapack! Check for duplicates.", target);
        }
        CONFIGS.put(target, data);
    }

    /**
     * Stores a runtime configuration linked to a unique item instance.
     */
    public static void setRuntimeStackConfig(String uuid, FoodBuffData data) {
        RUNTIME_STACK_CONFIGS.put(uuid, data);
    }

    /**
     * Persists runtime unique buffs to a file.
     */
    public static void saveRuntimeConfigs() {
        NbtCompound root = new NbtCompound();
        for (Map.Entry<String, FoodBuffData> entry : RUNTIME_STACK_CONFIGS.entrySet()) {
            root.put(entry.getKey(), entry.getValue().toNbt());
        }
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(RUNTIME_FILE_NAME);
            NbtIo.writeCompressed(root, path);
        } catch (Exception e) {
            System.err.println("Failed to save Florafare runtime buffs!");
            e.printStackTrace();
        }
    }

    /**
     * Loads runtime unique buffs from file.
     */
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
            System.err.println("Failed to load Florafare runtime buffs!");
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the appropriate configuration for the given ItemStack based on
     * priority: Unique Command Buff -> Exact Item ID -> Tag -> Namespace -> Default Template -> Auto-gen.
     */
    public static FoodBuffData getConfig(ItemStack stack) {
        if (stack.getItem() instanceof ForgottenMeadItem) {
            return null;
        }

        // 1. Priority: Unique items created via command
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

        // 2. Exact item ID
        String itemTarget = "item:" + itemId.toString();
        if (CONFIGS.containsKey(itemTarget)) return CONFIGS.get(itemTarget);

        // 3. Item tags
        for (TagKey<?> tag : stack.streamTags().toList()) {
            String tagTarget = "tag:" + tag.id().toString();
            if (CONFIGS.containsKey(tagTarget)) return CONFIGS.get(tagTarget);
        }

        // 4. Namespace
        String nsTarget = "namespace:" + itemId.getNamespace();
        if (CONFIGS.containsKey(nsTarget)) return CONFIGS.get(nsTarget);

        // 5. Template
        if (CONFIGS.containsKey("template:default")) return CONFIGS.get("template:default");

        // 6. Auto-generation
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
                new ArrayList<>()
        );
    }

    public static int getConfigCount() {
        return CONFIGS.size();
    }

    /**
     * Returns all datapack-loaded configurations for JEI/integration purposes.
     */
    public static List<FoodBuffData> getAllConfigs() {
        return new ArrayList<>(CONFIGS.values());
    }
}