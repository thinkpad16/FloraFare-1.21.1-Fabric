package net.tend1tnuy.florafare.config;

import net.fabricmc.loader.api.FabricLoader;
import net.tend1tnuy.florafare.food.FoodExclusions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a dedicated server does across a restart: an admin's {@code /florafare ignore}
 * edit is written to {@code config/florafare.json}, and the next startup reads it back
 * and puts the same rules in force.
 *
 * <p>Driven against the real {@link FlorafareConfig#save()} and {@link
 * FlorafareConfig#load()} — the actual file, the actual Gson mapping — because the ways
 * this breaks are all in the parts reasoning about it skips: a field added to the live
 * config but not to the serialized shape, a list read back as null, a save that writes
 * the defaults over someone's edit. None of those would show up in a test of the rules
 * alone, and all of them look the same from a server console: the exclusions were simply
 * gone after the restart.
 *
 * <p>The config directory is redirected into a temp folder for the duration. Fabric
 * Loader resolves it from a game directory that only exists once the game has launched,
 * so in a unit test it is null and every call through it would throw.
 */
class ConfigPersistenceTest {

    private static Path configDir;

    @BeforeEach
    void redirectConfigDir() throws Exception {
        if (configDir == null) {
            configDir = Files.createTempDirectory("florafare-config-test");
            configDir.toFile().deleteOnExit();

            Class<?> impl = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl");
            Object instance = impl.getField("INSTANCE").get(null);
            Field field = impl.getDeclaredField("configDir");
            field.setAccessible(true);
            field.set(instance, configDir);
        }
        assertEquals(configDir, FabricLoader.getInstance().getConfigDir(),
                "the redirect must be in place, or this test writes into the real config dir");

        Files.deleteIfExists(configDir.resolve("florafare.json"));
        Files.deleteIfExists(configDir.resolve("florafare.json.bak"));

        FlorafareConfig.ignoredFoodItems = new ArrayList<>();
        FlorafareConfig.ignoredFoodMods  = new ArrayList<>();
        FlorafareConfig.managedFoodItems = new ArrayList<>();
        clearExclusions();
    }

    /** A clean slate, through the public API the mod itself uses to replace them. */
    private static void clearExclusions() {
        FoodExclusions.clearRemote();
        FoodExclusions.loadFromConfig(List.of(), List.of(), List.of());
    }

    /** What the mod initializer does on startup, and nothing else. */
    private static void restart() {
        FlorafareConfig.ignoredFoodItems = new ArrayList<>();
        FlorafareConfig.ignoredFoodMods  = new ArrayList<>();
        FlorafareConfig.managedFoodItems = new ArrayList<>();
        clearExclusions();
        FlorafareConfig.load();
    }

    @Test
    @DisplayName("all three exclusion lists survive a restart")
    void listsSurviveARestart() {
        FlorafareConfig.ignoredFoodItems = new ArrayList<>(
                List.of("loot_n_explore:espresso", "#c:drinks"));
        FlorafareConfig.ignoredFoodMods  = new ArrayList<>(List.of("vinery"));
        FlorafareConfig.managedFoodItems = new ArrayList<>(List.of("vinery:mead"));
        FlorafareConfig.save();

        restart();

        assertEquals(List.of("loot_n_explore:espresso", "#c:drinks"),
                FlorafareConfig.ignoredFoodItems);
        assertEquals(List.of("vinery"), FlorafareConfig.ignoredFoodMods);
        assertEquals(List.of("vinery:mead"), FlorafareConfig.managedFoodItems);
    }

    @Test
    @DisplayName("and are back in force, not merely back in the fields")
    void rulesAreAppliedOnStartup() {
        // load() applying them is the whole reason /florafare ignore works after a
        // restart; the lists being read correctly would be worth nothing on its own.
        FlorafareConfig.ignoredFoodMods  = new ArrayList<>(List.of("vinery"));
        FlorafareConfig.managedFoodItems = new ArrayList<>(List.of("vinery:mead"));
        FlorafareConfig.save();

        restart();

        assertTrue(FoodExclusions.canonicalEntries(FoodExclusions.Effect.EXCLUDE)
                .contains("vinery:*"));
        assertTrue(FoodExclusions.canonicalEntries(FoodExclusions.Effect.MANAGE)
                .contains("vinery:mead"));
    }

    @Test
    @DisplayName("a rule deleted from the file is gone after the restart, not resurrected")
    void deletionSurvivesToo() {
        FlorafareConfig.ignoredFoodMods = new ArrayList<>(List.of("vinery", "loot_n_explore"));
        FlorafareConfig.save();
        restart();

        // What /florafare ignore remove does to the list, followed by another restart.
        FlorafareConfig.ignoredFoodMods.remove("vinery");
        FlorafareConfig.save();
        restart();

        assertEquals(List.of("loot_n_explore"), FlorafareConfig.ignoredFoodMods);
        assertFalse(FoodExclusions.canonicalEntries(FoodExclusions.Effect.EXCLUDE)
                .contains("vinery:*"));
    }

    @Test
    @DisplayName("a config file written before these lists existed still loads")
    void upgradeFromAnOlderFile() throws Exception {
        // Exactly what a 1.4 server leaves on disk: no ignoredFoodMods, no
        // managedFoodItems. Gson leaves absent fields at their defaults, and a null-check
        // in load() keeps those from overwriting the live lists with null.
        Files.writeString(configDir.resolve("florafare.json"), """
                {
                  "maxBuffSlots": 2,
                  "ignoredFoodItems": ["someothermod:stew"]
                }
                """);

        restart();

        assertEquals(2, FlorafareConfig.maxBuffSlots);
        assertEquals(List.of("someothermod:stew"), FlorafareConfig.ignoredFoodItems);
        assertNotNull(FlorafareConfig.ignoredFoodMods);
        assertNotNull(FlorafareConfig.managedFoodItems);
        assertTrue(FlorafareConfig.managedFoodItems.isEmpty());
    }

    @Test
    @DisplayName("saving replaces the file in one step, leaving no half-written config behind")
    void saveIsAtomic() throws Exception {
        FlorafareConfig.ignoredFoodMods = new ArrayList<>(List.of("vinery"));
        FlorafareConfig.save();

        // The temp file the write goes through must not survive its own success: left
        // behind, it is the copy an admin finds first and edits by mistake.
        assertFalse(Files.exists(configDir.resolve("florafare.json.tmp")),
                "the staging file should be gone once it has been moved into place");
        assertTrue(Files.exists(configDir.resolve("florafare.json")));
    }

    @Test
    @DisplayName("a half-written file is kept aside rather than overwritten")
    void truncatedFileIsPreserved() throws Exception {
        // Exactly what an in-place write killed part-way through leaves behind, which is
        // the failure the atomic save above exists to prevent. Should it ever happen
        // anyway — a crash while another tool writes the file, a full disk — the session
        // runs on defaults, but the admin's file is still there to fix. Silently
        // replacing it with a pristine one would destroy the only copy of their settings.
        String truncated = "{\n  \"maxBuffSlots\": 2,\n  \"ignoredFoodMods\": [\"vin";
        Files.writeString(configDir.resolve("florafare.json"), truncated);

        restart();

        assertEquals(truncated, Files.readString(configDir.resolve("florafare.json.bak")));
        assertEquals(truncated, Files.readString(configDir.resolve("florafare.json")),
                "the original must be left exactly as it was, not rewritten from defaults");
    }
}
