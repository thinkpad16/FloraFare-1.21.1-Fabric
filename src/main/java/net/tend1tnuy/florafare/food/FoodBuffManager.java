package net.tend1tnuy.florafare.food;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.item.ForgottenMeadItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manager for food buff configurations.
 * Handles datapack-loaded configs, runtime-generated command buffs, and fallback logic.
 */
public class FoodBuffManager {

    private static final Map<String, FoodBuffData> CONFIGS =
            new ConcurrentHashMap<>();

    /** How many command-created per-stack buffs a world keeps before evicting the oldest. */
    private static final int MAX_RUNTIME_CONFIGS = 4096;

    /**
     * Per-stack buffs created by {@code /florafare setbuff}, keyed by the UUID stamped
     * into the stack's custom data.
     *
     * <p>Bounded, and in insertion order, because nothing can tell when one of these
     * stops being referenced — the stack carrying the UUID may have been eaten, dropped
     * into lava, or lost with a chunk, and scanning every inventory in the world to find
     * out is not worth it. So the map keeps the {@value #MAX_RUNTIME_CONFIGS} most
     * recently created and discards older ones, instead of growing without limit for the
     * lifetime of the world.
     */
    private static final Map<String, FoodBuffData> RUNTIME_STACK_CONFIGS =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FoodBuffData> eldest) {
                    return size() > MAX_RUNTIME_CONFIGS;
                }
            });

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

    /**
     * Resolved config per item, so the lookup chain below runs once per item instead of
     * once per call. It matters because {@link #getConfig(ItemStack)} sits on genuinely
     * hot paths — the item tooltip and the HUD hover tooltip rebuild every frame while
     * the cursor rests on a food, {@code canConsume} asks twice a tick, and the journal
     * asks once per item in the registry every time it rebuilds. Uncached, the miss
     * path streams the stack's tags and allocates a fresh {@link FoodBuffData} with two
     * lists every single time.
     *
     * <p>Keyed by item, never by stack: the per-stack {@code /florafare setbuff}
     * override is resolved before this cache is consulted and is deliberately never
     * stored in it.
     */
    private static final Map<Item, FoodBuffData> RESOLVED_CACHE = new ConcurrentHashMap<>();

    /**
     * Stands in for "this item resolves to nothing", so a negative result is cached too
     * — {@link ConcurrentHashMap} cannot hold a null value, and the excluded/non-food
     * case is the one asked most often in a modded inventory.
     */
    private static final FoodBuffData NO_CONFIG =
            new FoodBuffData("florafare:none", 0, 0, 0f, 0, List.of(), List.of(), Integer.MIN_VALUE, false);

    private static final String RUNTIME_FILE_NAME = "florafare_runtime.dat";
    private static final String BUFF_ID_KEY       = "FlorafareBuffId";

    /**
     * Where a server keeps its command-created per-stack buffs.
     *
     * <p>In the world save, not the config directory. The buffs describe items that exist
     * in one particular world, so keeping them in {@code config/} meant every world on
     * the installation shared one pile of them: a UUID stamped onto a stack in one save
     * resolved in another, and deleting a world left its entries behind forever.
     */
    private static Path runtimeFilePath(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(RUNTIME_FILE_NAME);
    }

    /**
     * Multipliers the auto-generation path applies to a vanilla FoodComponent.
     *
     * <p>{@code volatile} for the same reason {@code PlayerFoodComponent.maxBuffSlots}
     * is: they are written from the datapack reload, from the server's config packet and
     * from the ModMenu screen, and read from the render thread every time a food tooltip
     * is drawn. Nothing here needs atomicity across the pair — a torn read would only
     * mean one tooltip frame built from a half-applied config — but a long-lived stale
     * read on a thread that never sees the write is worth ruling out for the price of a
     * keyword.
     */
    public static volatile int    AUTO_GEN_DURATION_MULT = 1200;
    public static volatile double AUTO_GEN_HEALTH_MULT   = 0.5;

    /**
     * Entries registered from code through
     * {@link net.tend1tnuy.florafare.api.FlorafareAPI#registerFoodBuff}.
     *
     * <p>Held separately from {@link #CONFIGS} because the datapack reload wipes that map
     * wholesale, and the reload runs when a world loads — i.e. after every mod
     * initializer has finished. An addon that registered its buffs exactly where the API
     * tells it to therefore had them deleted before the first player could ever connect.
     * They are replayed on every {@link #clear()}, ahead of the datapack entries, so the
     * usual priority rules settle any tie between the two.
     */
    private static final Map<String, FoodBuffData> API_CONFIGS = new ConcurrentHashMap<>();

    public static void clear() {
        CONFIGS.clear();
        AMBIGUOUS_TARGETS.clear();
        CONFIGS.putAll(API_CONFIGS);
        invalidateResolutionCache();
    }

    /**
     * Registers a config that survives datapack reloads. See {@link #API_CONFIGS}.
     */
    public static void putApiConfig(String target, FoodBuffData data) {
        API_CONFIGS.put(target, data);
        putConfig(target, data);
    }

    /**
     * Drops every memoized lookup. Called from each of the four things that can change
     * what an item resolves to: the datapack reload, a config arriving from the server,
     * a single entry being registered through the API, and the exclusion set changing.
     */
    /**
     * Bumped every time the config map, the exclusion set or the auto-generation
     * multipliers change — i.e. on every datapack load and every API call that alters
     * them. Anything that derives an expensive list from the configs can cache it and
     * compare this instead of rebuilding: see {@code SetBuffCommand#journalTargets}.
     */
    private static final AtomicInteger CONFIG_GENERATION = new AtomicInteger();

    /** @see #CONFIG_GENERATION */
    public static int configGeneration() {
        return CONFIG_GENERATION.get();
    }

    public static void invalidateResolutionCache() {
        CONFIG_GENERATION.incrementAndGet();
        RESOLVED_CACHE.clear();
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
        invalidateResolutionCache();
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

    /**
     * The config registered under an exact normalized target string, or null.
     * Unlike {@link #getConfig(ItemStack)} this performs no tag/namespace/auto-gen
     * resolution — it answers "what did a datapack define for this literal target".
     */
    public static FoodBuffData getConfigByTarget(String target) {
        return target == null ? null : CONFIGS.get(target);
    }

    /** Targets defined at the same priority by more than one source; see {@link #AMBIGUOUS_TARGETS}. */
    public static Set<String> getAmbiguousTargets() {
        return new HashSet<>(AMBIGUOUS_TARGETS);
    }

    public static void setRuntimeStackConfig(String uuid, FoodBuffData data) {
        RUNTIME_STACK_CONFIGS.put(uuid, data);
    }

    public static void saveRuntimeConfigs(MinecraftServer server) {
        NbtCompound root = new NbtCompound();
        // The map is synchronized, but iterating one still needs the lock held.
        synchronized (RUNTIME_STACK_CONFIGS) {
            for (Map.Entry<String, FoodBuffData> entry : RUNTIME_STACK_CONFIGS.entrySet()) {
                root.put(entry.getKey(), entry.getValue().toNbt());
            }
        }
        try {
            NbtIo.writeCompressed(root, runtimeFilePath(server));
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save Florafare runtime buffs!", e);
        }
    }

    public static void loadRuntimeConfigs(MinecraftServer server) {
        // Always starts from empty: these belong to the world being loaded, and anything
        // left in the map is the previous world's.
        RUNTIME_STACK_CONFIGS.clear();

        Path path = runtimeFilePath(server);
        if (!Files.exists(path)) {
            migrateLegacyRuntimeConfigs(server, path);
            return;
        }
        try {
            NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
            for (String key : root.getKeys()) {
                RUNTIME_STACK_CONFIGS.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to load Florafare runtime buffs!", e);
        }
    }

    /**
     * Copies a pre-1.4 {@code config/florafare_runtime.dat} into the world being loaded,
     * so stacks stamped by an older version keep working. The original is left alone —
     * other worlds on the same installation may still need to migrate from it too.
     */
    private static void migrateLegacyRuntimeConfigs(MinecraftServer server, Path destination) {
        Path legacy = FabricLoader.getInstance().getConfigDir().resolve(RUNTIME_FILE_NAME);
        if (!Files.exists(legacy)) return;
        try {
            NbtCompound root = NbtIo.readCompressed(legacy, NbtSizeTracker.ofUnlimitedBytes());
            for (String key : root.getKeys()) {
                RUNTIME_STACK_CONFIGS.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
            }
            saveRuntimeConfigs(server);
            Florafare.LOGGER.info(
                    "Migrated {} command-created buffs from {} into this world's save.",
                    root.getKeys().size(), legacy);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to migrate legacy Florafare runtime buffs!", e);
        }
    }

    /**
     * Marks an item id as fully ignored by Florafare. Safe to call from any mod's
     * initializer; take effect immediately server-side and are synced to clients
     * on their next join or {@code /reload}.
     */
    public static void excludeItem(String itemId) {
        Identifier id = normalizeItemId(itemId);
        if (id != null && EXCLUDED_ITEMS.add(id.toString())) invalidateResolutionCache();
    }

    public static void includeItem(String itemId) {
        Identifier id = normalizeItemId(itemId);
        if (id != null && EXCLUDED_ITEMS.remove(id.toString())) invalidateResolutionCache();
    }

    /**
     * The datapack half of the exclusion list.
     *
     * <p>{@link #EXCLUDED_ITEMS} can only be filled from {@code florafare.json} or from
     * another mod's initializer, neither of which a datapack or modpack author can reach.
     * Anything in this tag is ignored just as completely. The tag ships empty, so it
     * changes nothing until someone puts an item in it.
     */
    public static final TagKey<Item> IGNORED_TAG =
            TagKey.of(RegistryKeys.ITEM, Identifier.of(Florafare.MOD_ID, "ignored"));

    public static boolean isExcluded(ItemStack stack) {
        if (EXCLUDED_ITEMS.contains(Registries.ITEM.getId(stack.getItem()).toString())) {
            return true;
        }
        // Not folded into the resolution cache: tag membership is reloaded and re-synced
        // by vanilla on its own schedule, and this check is a single flag read on the
        // stack's registry entry — cheaper than the bookkeeping to memoize it would be.
        return stack.isIn(IGNORED_TAG);
    }

    public static Set<String> getExcludedItems() {
        return new HashSet<>(EXCLUDED_ITEMS);
    }

    /** Replaces the client-side exclusion set with data received from the server. */
    public static void setExcludedItems(Set<String> items) {
        EXCLUDED_ITEMS.clear();
        EXCLUDED_ITEMS.addAll(items);
        invalidateResolutionCache();
    }

    private static Identifier normalizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        return Identifier.tryParse(itemId.trim());
    }

    public static FoodBuffData getConfig(ItemStack stack) {
        if (stack.getItem() instanceof ForgottenMeadItem) return null;
        if (isExcluded(stack)) return null;

        // Check for a command-applied runtime buff first. Per-stack and therefore never
        // cached — two stacks of the same item can carry different ones.
        NbtComponent customDataComp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customDataComp != null) {
            NbtCompound nbt = customDataComp.copyNbt();
            if (nbt.contains(BUFF_ID_KEY)) {
                String uuid = nbt.getString(BUFF_ID_KEY);
                FoodBuffData runtime = RUNTIME_STACK_CONFIGS.get(uuid);
                if (runtime != null) return runtime;
            }
        }

        // A stack carrying its own "minecraft:food" component resolves differently from
        // the rest of its item — the auto-generation path below reads that component —
        // so it is resolved fresh and kept out of the per-item cache, which would
        // otherwise hand back (and store) the item's default answer for it.
        if (stack.getComponentChanges().get(DataComponentTypes.FOOD) != null) {
            return resolveConfig(stack);
        }

        FoodBuffData cached = RESOLVED_CACHE.get(stack.getItem());
        if (cached != null) return cached == NO_CONFIG ? null : cached;

        FoodBuffData resolved = resolveConfig(stack);
        RESOLVED_CACHE.put(stack.getItem(), resolved == null ? NO_CONFIG : resolved);
        return resolved;
    }

    /** The uncached lookup chain: item id, then tags, then namespace, then template, then auto-gen. */
    private static FoodBuffData resolveConfig(ItemStack stack) {
        // Florafare manages FOOD, and only food.
        //
        // The auto-generation branch at the bottom has always been gated on the item
        // actually being edible; the four explicit branches above it were not, so a
        // datapack with a "template:default" or "namespace:" entry — both documented as
        // covering "any food" — claimed every item in the game instead. The visible
        // result was a Florafare buff block on the tooltip of stone, swords and dirt, a
        // journal listing the entire item registry, and isAlwaysEdible() answering true
        // for things that are not food at all.
        //
        // Per-stack command buffs are resolved before this method is reached, so
        // /florafare setbuff can still stamp a buff onto a non-edible item on purpose.
        if (!stack.contains(DataComponentTypes.FOOD)) return null;

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

    // -------------------------------------------------------------------------
    // EXPLANATION
    //
    // Same chain as resolveConfig, walked with the reasoning kept. It exists because
    // "my datapack entry did nothing" is the single hardest thing to debug about this
    // mod: the entry may be shadowed by a more specific one, beaten by a priority, lost
    // to an exclusion, or never reached because the item is not food at all — and from
    // the outside all four look identical. /florafare explain prints this.
    //
    // Deliberately kept next to resolveConfig rather than in the command class, so the
    // two cannot drift apart unnoticed.
    // -------------------------------------------------------------------------

    /** Which rung of the resolution ladder decided this item's buff. */
    public enum Source {
        /** Forgotten Mead is the mod's own tool and is never buffed. */
        MEAD,
        /** On the exclusion list from the config file or another mod's API call. */
        EXCLUDED_BY_CONFIG,
        /** In the {@code #florafare:ignored} item tag. */
        EXCLUDED_BY_TAG,
        /** Not edible, so Florafare never looks at it. */
        NOT_FOOD,
        /** This exact stack carries a {@code /florafare setbuff} buff in its NBT. */
        STACK_BUFF,
        /** An entry whose {@code id} is this item id. */
        ITEM_ENTRY,
        /** An entry whose {@code id} is a tag this item is in. */
        TAG_ENTRY,
        /** An entry whose {@code id} is {@code namespace:<this item's namespace>}. */
        NAMESPACE_ENTRY,
        /** The {@code template:default} catch-all. */
        TEMPLATE_ENTRY,
        /** No entry matched; the buff was generated from the item's vanilla food values. */
        AUTO_GENERATED
    }

    /**
     * One entry that could have applied to the item.
     *
     * @param target   the entry's configured {@code id}
     * @param priority its effective priority
     * @param chosen   whether this is the one that won
     * @param reason   why it lost, or null for the winner and for entries never reached
     */
    public record Candidate(String target, int priority, boolean chosen, String reason) {}

    /** The full answer to "what does Florafare do with this item, and why". */
    public record Resolution(Source source, String target, FoodBuffData data,
                             List<Candidate> candidates) {}

    /** Walks the resolution chain for a stack, reporting every step. Never throws. */
    public static Resolution explain(ItemStack stack) {
        List<Candidate> candidates = new ArrayList<>();

        if (stack.getItem() instanceof ForgottenMeadItem) {
            return new Resolution(Source.MEAD, null, null, candidates);
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (EXCLUDED_ITEMS.contains(itemId.toString())) {
            return new Resolution(Source.EXCLUDED_BY_CONFIG, itemId.toString(), null, candidates);
        }
        if (stack.isIn(IGNORED_TAG)) {
            return new Resolution(Source.EXCLUDED_BY_TAG, "#" + IGNORED_TAG.id(), null, candidates);
        }

        NbtComponent customDataComp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customDataComp != null) {
            NbtCompound nbt = customDataComp.copyNbt();
            if (nbt.contains(BUFF_ID_KEY)) {
                FoodBuffData runtime = RUNTIME_STACK_CONFIGS.get(nbt.getString(BUFF_ID_KEY));
                if (runtime != null) {
                    return new Resolution(Source.STACK_BUFF, runtime.target(), runtime, candidates);
                }
            }
        }

        if (!stack.contains(DataComponentTypes.FOOD)) {
            return new Resolution(Source.NOT_FOOD, itemId.toString(), null, candidates);
        }

        // Every tag entry that could apply, ranked the way resolveConfig ranks them, is
        // collected even when an item entry wins — "my tag entry is being shadowed by an
        // item entry" is exactly the case this command has to be able to show.
        FoodBuffData bestTagMatch = null;
        List<FoodBuffData> tagMatches = new ArrayList<>();
        for (var tagEntry = stack.streamTags().iterator(); tagEntry.hasNext(); ) {
            TagKey<?> tag = tagEntry.next();
            FoodBuffData candidate = CONFIGS.get("#" + tag.id());
            if (candidate == null) continue;
            tagMatches.add(candidate);
            if (isBetterTagMatch(candidate, bestTagMatch)) bestTagMatch = candidate;
        }

        FoodBuffData itemEntry  = CONFIGS.get(itemId.toString());
        String       nsTarget   = "namespace:" + itemId.getNamespace();
        FoodBuffData nsEntry    = CONFIGS.get(nsTarget);
        FoodBuffData templEntry = CONFIGS.get("template:default");

        Source source;
        FoodBuffData winner;
        if (itemEntry != null) {
            source = Source.ITEM_ENTRY;
            winner = itemEntry;
        } else if (bestTagMatch != null) {
            source = Source.TAG_ENTRY;
            winner = bestTagMatch;
        } else if (nsEntry != null) {
            source = Source.NAMESPACE_ENTRY;
            winner = nsEntry;
        } else if (templEntry != null) {
            source = Source.TEMPLATE_ENTRY;
            winner = templEntry;
        } else {
            source = Source.AUTO_GENERATED;
            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            winner = food == null ? null : generateFromVanilla(food, itemId);
        }

        if (itemEntry != null) {
            candidates.add(new Candidate(itemEntry.target(), itemEntry.priority(), true, null));
        }
        for (FoodBuffData tagMatch : tagMatches) {
            boolean chosen = tagMatch == winner;
            candidates.add(new Candidate(tagMatch.target(), tagMatch.priority(), chosen,
                    chosen ? null : (itemEntry != null ? REASON_ITEM_ENTRY_WINS : REASON_TAG_RANK)));
        }
        if (nsEntry != null) {
            candidates.add(new Candidate(nsEntry.target(), nsEntry.priority(),
                    nsEntry == winner, nsEntry == winner ? null : REASON_MORE_SPECIFIC));
        }
        if (templEntry != null) {
            candidates.add(new Candidate(templEntry.target(), templEntry.priority(),
                    templEntry == winner, templEntry == winner ? null : REASON_MORE_SPECIFIC));
        }

        return new Resolution(source, winner == null ? null : winner.target(), winner, candidates);
    }

    /** Machine-readable loss reasons; the command turns them into translated text. */
    public static final String REASON_ITEM_ENTRY_WINS = "item_entry_wins";
    public static final String REASON_TAG_RANK        = "tag_rank";
    public static final String REASON_MORE_SPECIFIC   = "more_specific";

    /**
     * Ranks configured tag entries when an item belongs to several of them.
     * Higher priority wins; on ties the more specific (deeper path) tag wins.
     */
    static boolean isBetterTagMatch(FoodBuffData candidate, FoodBuffData current) {
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
        // Computed in long, then clamped. As int this overflowed: a modded food with
        // nutrition 20 and a duration multiplier in the millions — both values the config
        // accepted — wrapped to a negative duration, and a buff created negative is
        // already expired, so the food silently granted nothing at all.
        long rawDuration = (long) food.nutrition() * AUTO_GEN_DURATION_MULT;
        int durationTicks = (int) Math.min(Math.max(rawDuration, 0L),
                ActiveFoodBuff.MAX_DURATION_TICKS);

        double healthBonus = food.nutrition() * AUTO_GEN_HEALTH_MULT;

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
        invalidateResolutionCache();
        Map<String, FoodBuffData> incoming = new HashMap<>();
        for (String key : root.getKeys()) {
            incoming.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
        }
        CONFIGS.putAll(incoming);
        CONFIGS.keySet().retainAll(incoming.keySet());
    }
}
