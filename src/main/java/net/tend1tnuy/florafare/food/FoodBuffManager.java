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
     * lifetime of the world — and, below that cap, drops the ones no stack has been seen
     * carrying for {@code runtimeBuffRetentionDays} (see {@link #RUNTIME_LAST_SEEN}).
     */
    private static final Map<String, FoodBuffData> RUNTIME_STACK_CONFIGS =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FoodBuffData> eldest) {
                    return size() > MAX_RUNTIME_CONFIGS;
                }
            });

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

    // -------------------------------------------------------------------------
    // RUNTIME STACK BUFFS: LAST SEEN
    //
    // The 4096-entry cap above bounds the file, but only by throwing away the oldest
    // *created* entries — a buff an operator stamped on a prize stack a year ago went
    // before one stamped on a test apple yesterday and eaten an hour later. And below the
    // cap nothing was ever removed, so on most servers the file simply grew for the life
    // of the world. Each entry now carries the last time the server saw a stack carrying
    // it, and entries unseen for runtimeBuffRetentionDays are pruned when the world loads.
    // -------------------------------------------------------------------------

    /** Epoch millis each runtime id was last seen at; see {@link #touchRuntime}. */
    private static final Map<String, Long> RUNTIME_LAST_SEEN = new ConcurrentHashMap<>();

    /**
     * How stale a last-seen stamp may get before a sighting refreshes it. Retention is
     * measured in days, so an hour of slack costs nothing, and it keeps the hot path —
     * {@link #getConfig} runs every frame a stamped stack is hovered — to one map read.
     */
    static final long TOUCH_INTERVAL_MILLIS = 60L * 60L * 1000L;

    /** One day, for turning {@code runtimeBuffRetentionDays} into a maximum age. */
    public static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    /** Where the stamp is kept inside each entry's compound in {@code florafare_runtime.dat}. */
    private static final String LAST_SEEN_KEY = "FlorafareLastSeen";

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
        SOURCES.clear();
        PRIORITY_LOSSES.clear();
        CONFIGS.putAll(API_CONFIGS);
        for (String target : API_CONFIGS.keySet()) SOURCES.put(target, API_SOURCE);
        invalidateResolutionCache();
    }

    /**
     * Registers a config that survives datapack reloads. See {@link #API_CONFIGS}.
     */
    public static void putApiConfig(String target, FoodBuffData data) {
        API_CONFIGS.put(target, data);
        putConfig(target, data, API_SOURCE);
    }

    // -------------------------------------------------------------------------
    // WHERE EACH ENTRY CAME FROM, AND WHAT IT BEAT
    //
    // Two definitions of one target are settled by priority, and the loser used to
    // vanish without a trace. That is the mechanism working when a pack deliberately
    // outranks the bundled defaults — and the single most confusing thing about the mod
    // when a pack simply forgot to set a priority, because Florafare's own files sit at
    // 1 to 3 and an entry without one is 0. So every loss is now kept, with the file on
    // each side, for /florafare explain and /florafare validate to show.
    // -------------------------------------------------------------------------

    /** The source recorded for entries registered from code through the API. */
    public static final String API_SOURCE = "<api>";

    /**
     * The file (resource id, e.g. {@code mypack:meats}) each current entry was loaded
     * from, or {@link #API_SOURCE}. Written only by {@link #putConfig}, so an operator's
     * {@code /florafare edit} on top of an entry does not hide where the entry itself
     * came from.
     */
    private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();

    /** Every definition that lost to another this reload, in the order they lost. */
    private static final List<LostEntry> PRIORITY_LOSSES =
            Collections.synchronizedList(new ArrayList<>());

    /** A losing definition as recorded at the moment it lost; see {@link PriorityLoss}. */
    private record LostEntry(String target, String file, int priority) {}

    /**
     * One definition of a target that is not in force because another one won.
     *
     * @param target         the contested target
     * @param file           where the losing definition came from, or {@link #API_SOURCE}
     * @param priority       the losing definition's priority
     * @param winnerFile     where the definition in force now came from
     * @param winnerPriority its priority
     * @param tie            whether it has the same priority as the winner, so the load
     *                       order settled it rather than a higher priority. Judged against
     *                       the winner in force now, not the definition it first lost to.
     */
    public record PriorityLoss(String target, String file, int priority,
                               String winnerFile, int winnerPriority, boolean tie) {}

    /** What {@link #putConfig(String, FoodBuffData, String)} did with a definition. */
    public enum PutResult {
        /** The target was not defined before. */
        ADDED,
        /** It outranked the definition already there. */
        REPLACED,
        /** It tied with the definition already there and, being later, replaced it. */
        REPLACED_ON_TIE,
        /** The definition already there outranks it; it was discarded. */
        LOST
    }

    /** Where the entry in force for {@code target} was loaded from, or null if nothing defines it. */
    public static String sourceOf(String target) {
        return target == null ? null : SOURCES.get(target);
    }

    /**
     * Every definition that lost this reload, with the winner each lost to as things
     * stand now — a definition that beat one entry may itself have lost to a later one.
     */
    public static List<PriorityLoss> getPriorityLosses() {
        List<LostEntry> snapshot;
        synchronized (PRIORITY_LOSSES) {
            snapshot = new ArrayList<>(PRIORITY_LOSSES);
        }
        List<PriorityLoss> losses = new ArrayList<>(snapshot.size());
        for (LostEntry lost : snapshot) {
            FoodBuffData winner = CONFIGS.get(lost.target());
            int winnerPriority = winner == null ? 0 : winner.priority();
            losses.add(new PriorityLoss(lost.target(), lost.file(), lost.priority(),
                    SOURCES.getOrDefault(lost.target(), API_SOURCE),
                    winnerPriority, lost.priority() == winnerPriority));
        }
        return losses;
    }

    /** The definitions of one target that lost this reload; empty when it was uncontested. */
    public static List<PriorityLoss> getPriorityLosses(String target) {
        List<PriorityLoss> matching = new ArrayList<>();
        for (PriorityLoss loss : getPriorityLosses()) {
            if (loss.target().equals(target)) matching.add(loss);
        }
        return matching;
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

    /**
     * {@link #putConfig(String, FoodBuffData, String)} for a definition with no known file.
     * Still {@code void}, as it has been since 1.0, so code compiled against it keeps
     * linking.
     */
    public static void putConfig(String target, FoodBuffData data) {
        putConfig(target, data, null);
    }

    /**
     * Registers one definition of a target, settling a conflict with any definition
     * already there.
     *
     * <ul>
     *   <li>A higher priority replaces the existing definition, and settles the target:
     *       any earlier tie on it stops being ambiguous.</li>
     *   <li>An equal priority also replaces it — the later definition wins a tie — and
     *       flags the target as ambiguous, unless both came from the same file (inside
     *       one file the last entry wins, which is a decision the author can see).</li>
     *   <li>A lower priority is discarded and changes nothing else. In particular it does
     *       <em>not</em> clear the ambiguity flag: A and B tying at 1 is still a coin toss
     *       after C turns up at 0 and loses to both.</li>
     * </ul>
     *
     * <p>Which definition is "later" is the caller's to make deterministic: the datapack
     * listener feeds files in pack order, then by file id. Every loser is kept for
     * {@link #getPriorityLosses()}.
     *
     * @param source the file the definition came from, or null when unknown
     */
    public static PutResult putConfig(String target, FoodBuffData data, String source) {
        invalidateResolutionCache();
        String from = source == null ? API_SOURCE : source;
        FoodBuffData existing = CONFIGS.get(target);
        if (existing == null) {
            CONFIGS.put(target, data);
            SOURCES.put(target, from);
            return PutResult.ADDED;
        }

        String existingSource = SOURCES.getOrDefault(target, API_SOURCE);
        if (data.priority() > existing.priority()) {
            AMBIGUOUS_TARGETS.remove(target);
            PRIORITY_LOSSES.add(new LostEntry(target, existingSource, existing.priority()));
            CONFIGS.put(target, data);
            SOURCES.put(target, from);
            return PutResult.REPLACED;
        }
        if (data.priority() == existing.priority()) {
            // Unknown sources are never assumed to be the same file: two anonymous
            // definitions tying is exactly the case worth flagging.
            boolean sameFile = source != null && source.equals(existingSource);
            if (!sameFile) AMBIGUOUS_TARGETS.add(target);
            PRIORITY_LOSSES.add(new LostEntry(target, existingSource, existing.priority()));
            CONFIGS.put(target, data);
            SOURCES.put(target, from);
            return PutResult.REPLACED_ON_TIE;
        }
        PRIORITY_LOSSES.add(new LostEntry(target, from, data.priority()));
        return PutResult.LOST;
    }

    /**
     * Writes a config under a target unconditionally, ignoring the priority rules
     * {@link #putConfig} applies.
     *
     * <p>For {@link FoodBuffOverrides}, and only for it. Priority exists so two datapacks
     * defining the same target settle it without an operator refereeing; an operator
     * typing {@code /florafare edit} into a running server <em>is</em> the referee, and an
     * edit that silently lost to a number in a JSON file would be the worst possible
     * answer to "why did my command do nothing".
     */
    public static void forcePutConfig(String target, FoodBuffData data) {
        invalidateResolutionCache();
        AMBIGUOUS_TARGETS.remove(target);
        CONFIGS.put(target, data);
    }

    /**
     * Drops a target from the config map. Used when an override is reset and there was no
     * datapack entry underneath it to restore.
     */
    public static void removeConfig(String target) {
        invalidateResolutionCache();
        AMBIGUOUS_TARGETS.remove(target);
        CONFIGS.remove(target);
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

    /**
     * The server these per-stack buffs belong to, so a new one can be written the moment
     * it is created. Null between worlds, and in the tests.
     */
    private static volatile MinecraftServer runtimeOwner;

    /**
     * Records a buff stamped onto one stack by {@code /florafare setbuff}, and writes it
     * out immediately.
     *
     * <p>The write used to happen at {@code SERVER_STOPPING} and nowhere else, which made
     * every one of these last only as long as a clean shutdown. A server that was killed —
     * a crash, an OOM, a host pulling the plug — came back with the file as it was at the
     * previous stop, while the stacks themselves had survived in players' inventories with
     * their {@code FlorafareBuffId} intact. The UUID then resolved to nothing and the item
     * quietly became ordinary food, which is indistinguishable from the buff having been
     * removed on purpose.
     *
     * <p>Cheap to do eagerly: these are created one at a time by an operator running a
     * command, not on any hot path.
     */
    public static void setRuntimeStackConfig(String uuid, FoodBuffData data) {
        RUNTIME_STACK_CONFIGS.put(uuid, data);
        RUNTIME_LAST_SEEN.put(uuid, System.currentTimeMillis());
        // The one save per setbuff. The command used to call saveRuntimeConfigs() again
        // right after this, so every stamp rewrote the whole file twice.
        MinecraftServer server = runtimeOwner;
        if (server != null) saveRuntimeConfigs(server);
    }

    /**
     * Records that a stack carrying {@code uuid} was just seen, if the previous stamp is
     * more than {@link #TOUCH_INTERVAL_MILLIS} old. Only on the side that owns the file:
     * a remote client's copy of the map is never saved, so stamping it would be waste.
     */
    static void touchRuntime(String uuid, long now) {
        if (runtimeOwner == null) return;
        Long previous = RUNTIME_LAST_SEEN.get(uuid);
        if (previous == null || now - previous >= TOUCH_INTERVAL_MILLIS) {
            RUNTIME_LAST_SEEN.put(uuid, now);
        }
    }

    /**
     * Marks every stamped stack among these as seen. Called with a joining player's
     * inventory and ender chest, so a prize stack that is carried around but never
     * eaten or hovered is not pruned out from under its owner.
     */
    public static void touchRuntimeStacks(Iterable<ItemStack> stacks) {
        if (RUNTIME_STACK_CONFIGS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (ItemStack stack : stacks) {
            String uuid = runtimeIdOf(stack);
            if (uuid != null && RUNTIME_STACK_CONFIGS.containsKey(uuid)) touchRuntime(uuid, now);
        }
    }

    /** The {@code setbuff} id stamped on a stack, or null. */
    public static String runtimeIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return null;
        NbtCompound nbt = customData.copyNbt();
        return nbt.contains(BUFF_ID_KEY) ? nbt.getString(BUFF_ID_KEY) : null;
    }

    /**
     * The runtime ids whose last sighting is more than {@code maxAgeMillis} before
     * {@code now}, sorted. Nothing expires when {@code maxAgeMillis <= 0} ("keep
     * forever"), and a stamp from the future — a clock that was moved back — never does.
     */
    public static List<String> expiredRuntimeIds(Map<String, Long> lastSeen, long now, long maxAgeMillis) {
        if (maxAgeMillis <= 0) return List.of();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastSeen.entrySet()) {
            Long seen = entry.getValue();
            if (seen != null && now - seen > maxAgeMillis) expired.add(entry.getKey());
        }
        Collections.sort(expired);
        return expired;
    }

    /**
     * Drops every runtime stack buff not seen within {@code maxAgeMillis}.
     *
     * @return how many were dropped; the caller saves and re-syncs if that is not 0
     */
    public static int pruneRuntimeConfigs(long maxAgeMillis, long now) {
        List<String> expired;
        synchronized (RUNTIME_STACK_CONFIGS) {
            // Entries with no stamp at all (none should exist) count as seen now.
            Map<String, Long> stamps = new HashMap<>();
            for (String uuid : RUNTIME_STACK_CONFIGS.keySet()) {
                stamps.put(uuid, RUNTIME_LAST_SEEN.getOrDefault(uuid, now));
            }
            expired = expiredRuntimeIds(stamps, now, maxAgeMillis);
            for (String uuid : expired) RUNTIME_STACK_CONFIGS.remove(uuid);
        }
        for (String uuid : expired) RUNTIME_LAST_SEEN.remove(uuid);
        return expired.size();
    }

    /** How many {@code setbuff} stack buffs this world holds. */
    public static int getRuntimeConfigCount() {
        return RUNTIME_STACK_CONFIGS.size();
    }

    // -------------------------------------------------------------------------
    // RUNTIME STACK BUFFS: CLIENT SYNC
    //
    // These were never sent to clients at all. A remote client resolves a stamped stack
    // through getConfig like any other, found no entry for its id, and fell back to the
    // item's normal buff — so the tooltip, the journal and the client's hunger prediction
    // all described the default buff for a stack that grants something else entirely.
    // (Single-player never showed it: client and server share this map there.)
    // -------------------------------------------------------------------------

    /** Every runtime entry as {@code uuid -> FoodBuffData}, for the full sync on join. */
    public static NbtCompound serializeRuntimeConfigs() {
        NbtCompound root = new NbtCompound();
        synchronized (RUNTIME_STACK_CONFIGS) {
            for (Map.Entry<String, FoodBuffData> entry : RUNTIME_STACK_CONFIGS.entrySet()) {
                root.put(entry.getKey(), entry.getValue().toNbt());
            }
        }
        return root;
    }

    /**
     * Client side: takes runtime entries from the server. {@code replace} swaps in the
     * whole map (the join sync, a prune), otherwise the entries are merged in (one new
     * setbuff). Never called for a client's own integrated server, which shares the map.
     */
    public static void applyRuntimeConfigsFromNbt(NbtCompound root, boolean replace) {
        Map<String, FoodBuffData> incoming = new LinkedHashMap<>();
        for (String key : root.getKeys()) {
            incoming.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
        }
        synchronized (RUNTIME_STACK_CONFIGS) {
            if (replace) RUNTIME_STACK_CONFIGS.clear();
            RUNTIME_STACK_CONFIGS.putAll(incoming);
        }
    }

    /** Client side: forgets a remote server's runtime entries when the connection ends. */
    public static void clearRemoteRuntimeConfigs() {
        if (runtimeOwner != null) return;   // a world of our own: the map is the server's
        RUNTIME_STACK_CONFIGS.clear();
        RUNTIME_LAST_SEEN.clear();
    }

    /**
     * Forgets the world that has just closed, so an edit made with no server up cannot be
     * written into the save of one that is already gone. Matters in single-player, where
     * these statics outlive a world.
     */
    public static void clearRuntimeOwner() {
        runtimeOwner = null;
    }

    public static void saveRuntimeConfigs(MinecraftServer server) {
        NbtCompound root = new NbtCompound();
        long now = System.currentTimeMillis();
        // The map is synchronized, but iterating one still needs the lock held.
        synchronized (RUNTIME_STACK_CONFIGS) {
            for (Map.Entry<String, FoodBuffData> entry : RUNTIME_STACK_CONFIGS.entrySet()) {
                NbtCompound data = entry.getValue().toNbt();
                // Beside the entry's own fields, which FoodBuffData#fromNbt ignores: an
                // older Florafare reading this file loads the entries exactly as before.
                data.putLong(LAST_SEEN_KEY, RUNTIME_LAST_SEEN.getOrDefault(entry.getKey(), now));
                root.put(entry.getKey(), data);
            }
            // Stamps for entries the size cap evicted are dropped along with them.
            RUNTIME_LAST_SEEN.keySet().retainAll(RUNTIME_STACK_CONFIGS.keySet());
        }
        Path destination = runtimeFilePath(server);
        Path temp = destination.resolveSibling(RUNTIME_FILE_NAME + ".tmp");
        try {
            // Beside the file and then over it, never into it: now that this is written
            // on every setbuff rather than once at shutdown, it is genuinely exposed to
            // being half-written when a server dies, and a truncated NBT stream does not
            // cost the last entry — it fails to parse and costs every entry.
            NbtIo.writeCompressed(root, temp);
            Files.move(temp, destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            // FAT and some network mounts; a plain replace is still far better than
            // writing into the live file.
            try {
                Files.move(temp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to save Florafare runtime buffs!", e);
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save Florafare runtime buffs!", e);
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // The real error is already logged.
            }
        }
    }

    public static void loadRuntimeConfigs(MinecraftServer server) {
        // Always starts from empty: these belong to the world being loaded, and anything
        // left in the map is the previous world's.
        RUNTIME_STACK_CONFIGS.clear();
        RUNTIME_LAST_SEEN.clear();
        runtimeOwner = server;

        Path path = runtimeFilePath(server);
        if (!Files.exists(path)) {
            migrateLegacyRuntimeConfigs(server, path);
            return;
        }
        long now = System.currentTimeMillis();
        try {
            NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
            for (String key : root.getKeys()) {
                NbtCompound entry = root.getCompound(key);
                RUNTIME_STACK_CONFIGS.put(key, FoodBuffData.fromNbt(entry));
                // A file written before stamps existed starts every entry's clock now,
                // so upgrading never prunes anything on the spot.
                RUNTIME_LAST_SEEN.put(key, entry.contains(LAST_SEEN_KEY)
                        ? entry.getLong(LAST_SEEN_KEY) : now);
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to load Florafare runtime buffs!", e);
            return;
        }

        int days = net.tend1tnuy.florafare.config.FlorafareConfig.runtimeBuffRetentionDays;
        int pruned = pruneRuntimeConfigs(days * DAY_MILLIS, now);
        if (pruned > 0) {
            Florafare.LOGGER.info("Pruned {} /florafare setbuff stack buff(s) not seen for over {} day(s) "
                    + "(runtimeBuffRetentionDays); {} remain.", pruned, days, RUNTIME_STACK_CONFIGS.size());
            saveRuntimeConfigs(server);
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
            long now = System.currentTimeMillis();
            for (String key : root.getKeys()) {
                RUNTIME_STACK_CONFIGS.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
                RUNTIME_LAST_SEEN.put(key, now);
            }
            saveRuntimeConfigs(server);
            Florafare.LOGGER.info(
                    "Migrated {} command-created buffs from {} into this world's save.",
                    root.getKeys().size(), legacy);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to migrate legacy Florafare runtime buffs!", e);
        }
    }

    // -------------------------------------------------------------------------
    // EXCLUSIONS
    //
    // The rules themselves live in FoodExclusions, which owns the per-source
    // bookkeeping, the "whole mod" and "whole tag" forms, and the server override.
    // What stays here are the two entry points the rest of the mod already calls
    // through — isExcluded, checked first in getConfig, and the API's add/remove.
    // -------------------------------------------------------------------------

    /**
     * The datapack half of the exclusion list; see {@link FoodExclusions#IGNORED_TAG}.
     * Kept here as well because it has been part of the mod's public surface since 1.4
     * and addons reference it by this name.
     */
    public static final TagKey<Item> IGNORED_TAG = FoodExclusions.IGNORED_TAG;

    /**
     * Marks an item id — or a whole mod, or an item tag — as fully ignored by Florafare.
     * Safe to call from any mod's initializer; takes effect immediately server-side and
     * is synced to clients on their next join or {@code /reload}.
     *
     * @see FoodExclusions for the accepted spellings
     */
    public static void excludeItem(String itemId) {
        FoodExclusions.add(FoodExclusions.Source.API, itemId);
    }

    /** Reverses {@link #excludeItem}. */
    public static void includeItem(String itemId) {
        FoodExclusions.remove(FoodExclusions.Source.API, itemId);
    }

    /** Whether Florafare has been told to leave this stack's item alone. */
    public static boolean isExcluded(ItemStack stack) {
        return FoodExclusions.isExcluded(stack);
    }

    /** @see FoodExclusions#isExcluded(Item) */
    public static boolean isExcluded(Item item) {
        return FoodExclusions.isExcluded(item);
    }

    /**
     * The bare item ids currently excluded.
     *
     * <p>Cannot express the mod- and tag-wide rules, so it is no longer what the client
     * sync carries — {@link FoodExclusions#canonicalEntries()} is. Kept because it has
     * been public since 1.4.
     */
    public static Set<String> getExcludedItems() {
        return FoodExclusions.excludedItemIds();
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
                if (runtime != null) {
                    touchRuntime(uuid, System.currentTimeMillis());
                    return runtime;
                }
            }
        }

        // A stack carrying its own "minecraft:food" component resolves differently from
        // the rest of its item — the auto-generation path below reads that component —
        // so it is resolved fresh and kept out of the per-item cache, which would
        // otherwise hand back (and store) the item's default answer for it.
        if (stack.getComponentChanges().get(DataComponentTypes.FOOD) != null) {
            return resolveConfig(stack);
        }

        Item item = stack.getItem();
        FoodBuffData cached = RESOLVED_CACHE.get(item);
        if (cached != null) return cached == NO_CONFIG ? null : cached;

        // Check-then-put across threads: in single-player the client (tooltips, HUD) and
        // the integrated server (canConsume, eating) both fill this cache, and a reload or
        // config sync can invalidate it between the resolve and the put. Unguarded, the
        // put then stored an answer computed from the previous configs into the freshly
        // cleared cache, where it stood until the next invalidation — a food granting
        // last reload's buff for the rest of the session. So the generation is captured
        // first, and an answer is only kept if nothing was invalidated in the meantime.
        // The second check, after the put, closes the window between the first check and
        // the put itself: an invalidation landing there bumps the counter before it
        // clears, so either its clear removes the entry or this removes it.
        int generation = CONFIG_GENERATION.get();
        FoodBuffData resolved = resolveConfig(stack);
        FoodBuffData toCache = resolved == null ? NO_CONFIG : resolved;
        if (CONFIG_GENERATION.get() == generation) {
            RESOLVED_CACHE.put(item, toCache);
            if (CONFIG_GENERATION.get() != generation) RESOLVED_CACHE.remove(item, toCache);
        }
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
        /** Named item-by-item on the exclusion list. */
        EXCLUDED_BY_CONFIG,
        /** Its whole mod is on the exclusion list. */
        EXCLUDED_BY_MOD,
        /** In an excluded item tag — {@code #florafare:ignored}, or one named in the config. */
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
        FoodExclusions.Entry exclusion = FoodExclusions.matchedRule(stack.getItem());
        if (exclusion != null) {
            Source excludedBy = switch (exclusion.kind()) {
                case ITEM -> Source.EXCLUDED_BY_CONFIG;
                case MOD  -> Source.EXCLUDED_BY_MOD;
                case TAG  -> Source.EXCLUDED_BY_TAG;
            };
            return new Resolution(excludedBy, exclusion.canonical(), null, candidates);
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

        // Hunger is inherited rather than copied. A copy restated the item's numbers as
        // Florafare's own, so the eat hook replaced vanilla's HungerManager#eat with an
        // add() of the same values — identical for most foods, but not for one with
        // saturation and no nutrition (no modifier reproduces it), and it stepped over any
        // other mod wrapping that call. Inherited, the bite's hunger is vanilla's, period.
        return new FoodBuffData(
                itemId.toString(), durationTicks, FoodBuffData.INHERIT_NUTRITION,
                FoodBuffData.INHERIT_SATURATION, healthBonus,
                new ArrayList<>(), new ArrayList<>(), 0, food.canAlwaysEat());
    }

    /**
     * Whether a resolved config was generated from the item's own food values rather
     * than written by someone — a datapack entry, an API registration, an operator's
     * {@code /florafare edit}, or a {@code /florafare setbuff} stack.
     *
     * <p>It decides whether Florafare may take the food's vanilla status effects away.
     * An explicit entry's {@code effects} list is a statement about what the food should
     * do, so the vanilla ones are replaced by it. A generated config has an empty list
     * only because nobody wrote one, and suppressing the vanilla effects on that basis
     * stripped every modded food Florafare had merely noticed — a Farmer's Delight meal's
     * Nourishment, a modded stew's Regeneration — of its whole point. Left alone, those
     * effects also keep their vanilla probabilities, which no copy of them could.
     *
     * <p>Answered against the config map: every explicit config is stored there under its
     * own target (or is a per-stack runtime one), while a generated one is built on demand
     * and never stored. Compared by value after the identity check, not by identity alone:
     * in single-player the client's config sync swaps equal copies into the very map the
     * server thread is resolving from, and a bite landing in that instant must not be
     * mistaken for an auto-generated one.
     */
    public static boolean isAutoGenerated(FoodBuffData data) {
        if (data == null || data == NO_CONFIG) return false;
        if (data.target().startsWith("stack:")) return false;
        FoodBuffData registered = CONFIGS.get(data.target());
        return registered == null || (registered != data && !registered.equals(data));
    }

    // -------------------------------------------------------------------------
    // POTIONS
    //
    // A "potion:<potion id>" entry targets a brewed potion rather than an item: every
    // drinkable potion is the one item minecraft:potion, told apart only by its
    // potion_contents component, so the target is keyed by the Potion registry id —
    // potion:minecraft:strength, potion:minecraft:long_swiftness. Drinking one grants
    // the entry's buff and effects on top of the potion's own (which stay vanilla), and
    // records the target itself as the journal entry, since there is no per-potion item
    // id to record instead. No hunger changes hands: a potion is not food.
    // -------------------------------------------------------------------------

    /** The prefix of a potion target. */
    public static final String POTION_PREFIX = "potion:";

    /**
     * The {@code potion:} target a drinkable stack corresponds to, or null for anything
     * that is not a potion with a registered base potion (an empty bottle, a potion
     * carrying only custom effects).
     */
    public static String potionTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        net.minecraft.component.type.PotionContentsComponent contents =
                stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty()) return null;
        return contents.potion().get().getKey()
                .map(key -> POTION_PREFIX + key.getValue())
                .orElse(null);
    }

    /**
     * The config a potion being drunk resolves to, or null. Exact target only — the
     * tag/namespace/template ladder is for food and never claims a potion — and the
     * exclusion list is honored, so excluding {@code minecraft:potion} switches the whole
     * feature off.
     */
    public static FoodBuffData getPotionConfig(ItemStack stack) {
        String target = potionTarget(stack);
        if (target == null || isExcluded(stack)) return null;
        return CONFIGS.get(target);
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
     * Applies one entry the server changed while the client was connected.
     *
     * <p>Kept apart from {@link #loadConfigsFromNbt}, which replaces the whole map: this
     * one must leave every other entry alone, because it is delivered on its own and the
     * rest of the map was never re-sent. In single-player it is a no-op against the map
     * the integrated server already edited, which is exactly right.
     */
    public static void applyConfigEntry(String target, FoodBuffData data) {
        invalidateResolutionCache();
        if (data == null) {
            CONFIGS.remove(target);
        } else {
            CONFIGS.put(target, data);
        }
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
