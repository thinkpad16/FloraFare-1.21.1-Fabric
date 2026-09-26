package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.tend1tnuy.florafare.Florafare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Buff configs an operator changed from inside the running server, and the layer that
 * keeps them in force.
 *
 * <h2>Why this exists</h2>
 * Everything else that defines a buff is decided before the server is up: a datapack file
 * read at world load, or a {@code FlorafareAPI} call from a mod initializer. Changing any
 * of it meant editing JSON on disk and running {@code /reload} — fine for a single-player
 * world, considerably less fine on a live server with players on it, where {@code /reload}
 * re-reads every datapack in the pack and stalls the tick loop while it does.
 *
 * <p>So {@code /florafare edit} writes here instead. An override is a complete
 * {@link FoodBuffData} for one target, it takes effect on the tick the command runs, it is
 * on disk before the command returns, and it survives both {@code /reload} and a restart.
 *
 * <h2>Where it sits in the resolution chain</h2>
 * Nowhere new. An override is pushed into {@code FoodBuffManager}'s config map under its
 * own target, so {@code getConfig} resolves exactly as it always has — item id, then tag,
 * then namespace, then template, then auto-generation. Overriding {@code #florafare:meats}
 * therefore still loses to an item-id entry for {@code minecraft:beef}, which is what an
 * operator overriding a whole tag means.
 *
 * <p>What it does <em>not</em> do is compete on priority. It is pushed through
 * {@link FoodBuffManager#forcePutConfig}, not {@code putConfig}: a datapack's priority
 * numbers settle arguments between datapacks, and an operator typing a command into a live
 * server is not another datapack. Losing an edit silently to a number in a JSON file
 * nobody remembers writing is the exact failure this command exists to end.
 *
 * <h2>Undo</h2>
 * Removing an override has to put back whatever it displaced, and the displaced value is
 * not recoverable from the config map once it has been written over. So the value each
 * override replaced is kept beside it in {@link #baseline}, captured fresh on every
 * datapack load — because that is when the thing being displaced changes. A reset then
 * restores the datapack's own entry, or drops the target entirely if there never was one.
 *
 * <h2>Storage</h2>
 * In the world save, for the same reason {@code florafare_runtime.dat} is: these describe
 * the balance of one world, and an installation hosting several must not share them.
 * Written on every change rather than at shutdown — there are a handful of them, each a
 * few dozen bytes, and the whole point is that an operator's edit is not lost if the
 * server is killed rather than stopped.
 */
public final class FoodBuffOverrides {

    private FoodBuffOverrides() {}

    private static final String FILE_NAME = "florafare_overrides.dat";

    private static final Object LOCK = new Object();

    /**
     * Target -> the config an operator put there. Insertion-ordered so
     * {@code /florafare edit list} reads back in the order the edits were made, which is
     * the order an operator remembers them in.
     */
    private static final Map<String, FoodBuffData> overrides = new LinkedHashMap<>();

    /**
     * Target -> what {@link #overrides} displaced, or null where nothing was defined for
     * that target at all. A {@link LinkedHashMap} and not a concurrent map precisely
     * because null is a meaningful value here.
     */
    private static final Map<String, FoodBuffData> baseline = new LinkedHashMap<>();

    /** The server whose save the overrides belong to; null between worlds. */
    private static MinecraftServer owner;

    // -------------------------------------------------------------------------
    // QUERIES
    // -------------------------------------------------------------------------

    /** The override for a target, or null if the operator has not touched it. */
    public static FoodBuffData get(String target) {
        synchronized (LOCK) {
            return overrides.get(target);
        }
    }

    /** Every override, newest edit last. A copy — callers may iterate it freely. */
    public static Map<String, FoodBuffData> entries() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(overrides);
        }
    }

    public static int count() {
        synchronized (LOCK) {
            return overrides.size();
        }
    }

    /** Whether a reset of this target would restore a datapack entry rather than remove it. */
    public static boolean hasBaseline(String target) {
        synchronized (LOCK) {
            return baseline.get(target) != null;
        }
    }

    // -------------------------------------------------------------------------
    // MUTATION
    // -------------------------------------------------------------------------

    /**
     * Installs or replaces one override, in memory and on disk.
     *
     * <p>The baseline is captured only the first time a target is overridden. Editing the
     * same target a second time must not record the first edit as the thing a reset goes
     * back to, or {@code reset} would walk edits back one at a time instead of undoing
     * them.
     */
    public static void put(String target, FoodBuffData data) {
        synchronized (LOCK) {
            if (!overrides.containsKey(target)) {
                baseline.put(target, FoodBuffManager.getConfigByTarget(target));
            }
            overrides.put(target, data);
            FoodBuffManager.forcePutConfig(target, data);
        }
        save();
    }

    /**
     * Drops one override and puts back what it displaced.
     *
     * @return false if that target was not overridden
     */
    public static boolean reset(String target) {
        synchronized (LOCK) {
            if (!overrides.containsKey(target)) return false;
            overrides.remove(target);
            restoreBaseline(target);
        }
        save();
        return true;
    }

    /** Drops every override. @return how many there were */
    public static int resetAll() {
        int removed;
        synchronized (LOCK) {
            removed = overrides.size();
            if (removed == 0) return 0;
            // Copied first: restoreBaseline writes to the same map it is iterating over.
            for (String target : new LinkedHashMap<>(overrides).keySet()) {
                restoreBaseline(target);
            }
            overrides.clear();
        }
        save();
        return removed;
    }

    /** Called with LOCK held. */
    private static void restoreBaseline(String target) {
        FoodBuffData base = baseline.remove(target);
        if (base != null) {
            FoodBuffManager.forcePutConfig(target, base);
        } else {
            FoodBuffManager.removeConfig(target);
        }
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------------------------

    /**
     * Re-asserts every override over the config map, and re-reads what each one displaces.
     *
     * <p>Called at the tail of the food datapack reload, which is the only thing that
     * rebuilds the config map — on world load and on {@code /reload} alike. Both halves
     * matter: without the re-assert a {@code /reload} would silently revert every
     * operator edit to the JSON on disk, and without re-capturing the baseline a later
     * reset would restore an entry the reloaded datapack no longer defines.
     */
    public static void reapply() {
        synchronized (LOCK) {
            baseline.clear();
            for (Map.Entry<String, FoodBuffData> entry : overrides.entrySet()) {
                baseline.put(entry.getKey(),
                        FoodBuffManager.getConfigByTarget(entry.getKey()));
                FoodBuffManager.forcePutConfig(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Reads the overrides belonging to the world being opened, and puts them in force.
     *
     * <p>The re-assert at the end is not belt-and-braces — it is the only thing that
     * applies them on a restart. The world's datapacks are read before the server object
     * exists at all (1.21 builds the dynamic registries from them in {@code Main}, on the
     * main thread), so by the time any server lifecycle event can fire, the food config
     * map has already been built once with this map still empty. Without the call, a
     * restarted server listed every override and enforced none of them.
     *
     * <p>The {@link #reapply} inside the reload listener still matters: it covers
     * {@code /reload}, which rebuilds the map again with the server already up.
     */
    public static void load(MinecraftServer server) {
        synchronized (LOCK) {
            owner = server;
            // Always from empty: these belong to the world being loaded, and in
            // single-player anything left behind is the previous world's.
            overrides.clear();
            baseline.clear();

            Path path = filePath(server);
            // Nothing to read and, the map having just been cleared, nothing to assert.
            if (!Files.exists(path)) return;
            try {
                NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
                for (String key : root.getKeys()) {
                    overrides.put(key, FoodBuffData.fromNbt(root.getCompound(key)));
                }
                Florafare.LOGGER.info("Loaded {} Florafare buff overrides from this world's save.",
                        overrides.size());
            } catch (Exception e) {
                // Left in memory as empty rather than aborting the world load: the
                // datapack's own values are a working server, a missing override is not
                // worth refusing to start over, and the file is still there to inspect.
                Florafare.LOGGER.error("Failed to load Florafare buff overrides from {}. "
                        + "The datapack's own values are in force for this session.", path, e);
            }
        }
        reapply();
    }

    /** Forgets the world that has just closed, so the next one starts clean. */
    public static void unload() {
        synchronized (LOCK) {
            owner = null;
            overrides.clear();
            baseline.clear();
        }
    }

    private static Path filePath(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME);
    }

    /**
     * Writes the overrides out now.
     *
     * <p>Deliberately not called with {@link #LOCK} held — the write touches the disk, and
     * holding the lock across it would stall the command thread behind it. The snapshot is
     * taken under the lock; a concurrent edit simply means two writes, the later of which
     * wins, which is the right answer for a file that is a full snapshot anyway.
     */
    private static void save() {
        MinecraftServer server;
        NbtCompound root = new NbtCompound();
        synchronized (LOCK) {
            server = owner;
            for (Map.Entry<String, FoodBuffData> entry : overrides.entrySet()) {
                root.put(entry.getKey(), entry.getValue().toNbt());
            }
        }
        // No world open: the tests, and any edit made before SERVER_STARTING could run.
        if (server == null) return;

        Path destination = filePath(server);
        Path temp = destination.resolveSibling(FILE_NAME + ".tmp");
        try {
            // Written beside the real file and moved over it, never into it. This file is
            // rewritten on every single edit, so it is the one Florafare writes most often
            // and therefore the one most likely to be half-written when a server is killed
            // — and a truncated NBT stream does not read back as "a few lost edits", it
            // throws, and the whole world's balance falls back to the datapack at once.
            NbtIo.writeCompressed(root, temp);
            replaceAtomically(temp, destination);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save Florafare buff overrides! The change is "
                    + "live for this session but will be lost on restart.", e);
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // Nothing useful to do about a leftover temp file, and the real error is
                // already logged above.
            }
        }
    }

    /**
     * Moves {@code temp} over {@code destination}, atomically where the filesystem can.
     *
     * <p>{@code ATOMIC_MOVE} is refused by some filesystems (FAT, a few network mounts),
     * and declining to save at all there would be the wrong trade. The plain replace it
     * falls back to is still far better than writing into the live file: the window in
     * which the destination is incomplete shrinks from "however long serialization takes"
     * to one filesystem operation.
     */
    private static void replaceAtomically(Path temp, Path destination) throws java.io.IOException {
        try {
            Files.move(temp, destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Test seam: drops everything without touching a world save. */
    static void resetForTests() {
        synchronized (LOCK) {
            owner = null;
            overrides.clear();
            baseline.clear();
        }
    }
}
