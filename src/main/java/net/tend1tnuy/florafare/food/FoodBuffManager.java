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
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.item.ForgottenMeadItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for food buff configurations.
 * Handles datapack-loaded configs, runtime-generated command buffs, and fallback logic.
 */
public class FoodBuffManager {

    private static final Map<String, FoodBuffData> CONFIGS =
            new ConcurrentHashMap<>();
    private static final Map<String, FoodBuffData> RUNTIME_STACK_CONFIGS =
            new ConcurrentHashMap<>();

    /**
     * Item ids Florafare fully ignores, for interop with other food/hunger mods.
     * Populated from {@code florafare.json}'s {@code ignoredFoodItems} and from
     * {@link net.tend1tnuy.florafare.api.FlorafareAPI#excludeFood(String)}. Checked
     * before anything else in {@link #getConfig(ItemStack)}, so an excluded item is
     * completely invisible to every Florafare hook (eating, tooltips, AppleSkin,
     * always-edible). Synced to clients so tooltip stripping and hunger prediction
     * stay consistent with the server's authoritative decision.
     */
    private static final Set<String> EXCLUDED_ITEMS = ConcurrentHashMap.newKeySet();

    /**
     * Targets that were defined by two or more datapack/API entries at the exact
     * same priority, so which one ended up winning was not a deliberate choice.
     * Tracked for {@code /florafare validate}; harmless to gameplay either way.
     */
    private static final Set<String> AMBIGUOUS_TARGETS = ConcurrentHashMap.newKeySet();

    private static final String RUNTIME_FILE_NAME = "florafare_runtime.dat";
    private static final String BUFF_ID_KEY       = "FlorafareBuffId";

    public static int    AUTO_GEN_DURATION_MULT = 1200;
    public static double AUTO_GEN_HEALTH_MULT   = 0.5;

    public static void clear() {
        CONFIGS.clear();
        AMBIGUOUS_TARGETS.clear();
    }

    /**
     * Normalizes a datapack/command target string into the canonical internal form.
     * Bare item ids ("minecraft:bread") and "#"-prefixed tag ids ("#c:foods") pass
     * through after namespace normalization. Special prefixes
     * ("namespace:", "template:", "potion:", "stack:") are returned unchanged.
     */
    public static String normalizeTarget(String raw) {
        if (raw == null) return null;
        String target = raw.trim();
        if (target.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(target.substring(1));
            return tagId != null ? "#" + tagId : target;
        }
        if (target.startsWith("namespace:") || target.startsWith("template:")
                || target.startsWith("potion:")   || target.startsWith("stack:")) {
            return target;
        }
        Identifier id = Identifier.tryParse(target);
        return id != null ? id.toString() : target;
    }

    public static void putConfig(String target, FoodBuffData data) {
        if (CONFIGS.containsKey(target)) {
            FoodBuffData existing = CONFIGS.get(target);
            if (data.priority() == existing.priority()) {
                AMBIGUOUS_TARGETS.add(target);
            } else {
                AMBIGUOUS_TARGETS.remove(target);
            }
            if (data.priority() >= existing.priority()) {
                CONFIGS.put(target, data);
            }
        } else {
            CONFIGS.put(target, data);
        }
    }

    /** Targets defined at the same priority by more than one source; see {@link #AMBIGUOUS_TARGETS}. */
    public static Set<String> getAmbiguousTargets() {
        return new HashSet<>(AMBIGUOUS_TARGETS);
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
                RUNTIME_STACK_CONFIGS.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to load Florafare runtime buffs!", e);
        }
    }

    /**
     * Marks an item id as fully ignored by Florafare. Safe to call from any mod's
     * initializer; take effect immediately server-side and are synced to clients
     * on their next join or {@code /reload}.
     */
    public static void excludeItem(String itemId) {
        Identifier id = normalizeItemId(itemId);
        if (id != null) EXCLUDED_ITEMS.add(id.toString());
    }

    public static void includeItem(String itemId) {
        Identifier id = normalizeItemId(itemId);
        if (id != null) EXCLUDED_ITEMS.remove(id.toString());
    }

    public static boolean isExcluded(ItemStack stack) {
        return EXCLUDED_ITEMS.contains(Registries.ITEM.getId(stack.getItem()).toString());
    }

    public static Set<String> getExcludedItems() {
        return new HashSet<>(EXCLUDED_ITEMS);
    }

    /** Replaces the client-side exclusion set with data received from the server. */
    public static void setExcludedItems(Set<String> items) {
        EXCLUDED_ITEMS.clear();
        EXCLUDED_ITEMS.addAll(items);
    }

    private static Identifier normalizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        return Identifier.tryParse(itemId.trim());
    }

    public static FoodBuffData getConfig(ItemStack stack) {
        if (stack.getItem() instanceof ForgottenMeadItem) return null;
        if (isExcluded(stack)) return null;

        // Check for a command-applied runtime buff first.
        NbtComponent customDataComp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customDataComp != null) {
            NbtCompound nbt = customDataComp.copyNbt();
            if (nbt.contains(BUFF_ID_KEY)) {
                String uuid = nbt.getString(BUFF_ID_KEY);
                FoodBuffData runtime = RUNTIME_STACK_CONFIGS.get(uuid);
                if (runtime != null) return runtime;
            }
        }

        Identifier itemId     = Registries.ITEM.getId(stack.getItem());
        String     itemTarget = itemId.toString();

        // Exact item-id match.
        if (CONFIGS.containsKey(itemTarget)) return CONFIGS.get(itemTarget);

        // Tag match — consume the stream directly without materializing a List (#14).
        FoodBuffData bestTagMatch = null;
        for (var tagEntry = stack.streamTags().iterator(); tagEntry.hasNext(); ) {
            TagKey<?> tag = tagEntry.next();
            FoodBuffData candidate = CONFIGS.get("#" + tag.id().toString());
            if (candidate != null && isBetterTagMatch(candidate, bestTagMatch)) {
                bestTagMatch = candidate;
            }
        }
        if (bestTagMatch != null) return bestTagMatch;

        // Namespace fallback.
        String nsTarget = "namespace:" + itemId.getNamespace();
        if (CONFIGS.containsKey(nsTarget)) return CONFIGS.get(nsTarget);

        // Template fallback.
        if (CONFIGS.containsKey("template:default")) return CONFIGS.get("template:default");

        // Auto-generate from vanilla FoodComponent.
        FoodComponent foodComponent = stack.get(DataComponentTypes.FOOD);
        if (foodComponent != null) return generateFromVanilla(foodComponent, itemId);

        return null;
    }

    /**
     * Ranks configured tag entries when an item belongs to several of them.
     * Higher priority wins; on ties the more specific (deeper path) tag wins.
     */
    private static boolean isBetterTagMatch(FoodBuffData candidate, FoodBuffData current) {
        if (current == null) return true;
        if (candidate.priority() != current.priority()) {
            return candidate.priority() > current.priority();
        }
        int candidateDepth = candidate.target().split("/").length;
        int currentDepth   = current.target().split("/").length;
        if (candidateDepth != currentDepth) return candidateDepth > currentDepth;
        if (candidate.target().length() != current.target().length()) {
            return candidate.target().length() > current.target().length();
        }
        return candidate.target().compareTo(current.target()) < 0;
    }

    private static FoodBuffData generateFromVanilla(FoodComponent food, Identifier itemId) {
        int    durationTicks = food.nutrition() * AUTO_GEN_DURATION_MULT;
        double healthBonus   = food.nutrition() * AUTO_GEN_HEALTH_MULT;

        // FoodComponent#saturation() is the FINAL saturation value; convert back to modifier.
        float saturationModifier = food.nutrition() > 0
                ? food.saturation() / (food.nutrition() * 2.0f) : 0.0f;

        return new FoodBuffData(
                itemId.toString(), durationTicks, food.nutrition(),
                saturationModifier, healthBonus,
                new ArrayList<>(), new ArrayList<>(), 0, food.canAlwaysEat());
    }

    /**
     * Whether the given stack should be eatable even at full hunger, per its
     * resolved Florafare buff config (datapack "always_edible", or the underlying
     * vanilla FoodComponent's own flag when no explicit config applies).
     */
    public static boolean isAlwaysEdible(ItemStack stack) {
        FoodBuffData data = getConfig(stack);
        return data != null && data.alwaysEdible();
    }

    public static int getConfigCount() { return CONFIGS.size(); }

    public static List<FoodBuffData> getAllConfigs() {
        return new ArrayList<>(CONFIGS.values());
    }

    /**
     * Resolves a requirement string to a list of matching ItemStacks.
     * Supports "#"-prefixed tag ids and plain item ids.
     *
     * Previously duplicated in {@code FoodJournalScreen} (#24); now the single
     * canonical implementation lives here so both the journal and any future
     * callers share the same logic.
     */
    public static List<ItemStack> resolveRequirementStacks(String req) {
        List<ItemStack> stacks = new ArrayList<>();
        if (req.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(req.substring(1));
            if (tagId != null) {
                for (RegistryEntry<net.minecraft.item.Item> entry
                        : Registries.ITEM.iterateEntries(
                                TagKey.of(RegistryKeys.ITEM, tagId))) {
                    stacks.add(entry.value().getDefaultStack());
                }
            }
        } else {
            Identifier id = Identifier.tryParse(req);
            if (id != null && Registries.ITEM.containsId(id)) {
                stacks.add(Registries.ITEM.get(id).getDefaultStack());
            }
        }
        return stacks;
    }

    /**
     * Serializes the entire resolved config map into NBT for server-to-client sync.
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
     * Updates in place (put then retain) so the integrated server thread never
     * observes a momentarily empty map in singleplayer.
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
