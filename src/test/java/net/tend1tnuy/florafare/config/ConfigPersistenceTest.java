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

    // -------------------------------------------------------------------------
    // /reload
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("/reload re-reads the server settings and puts the new exclusions in force")
    void reloadRereadsServerSettings() throws Exception {
        FlorafareConfig.maxBuffSlots = 3;
        FlorafareConfig.commandPermissionLevel = 2;
        Files.writeString(configDir.resolve("florafare.json"), """
                {
                  "maxBuffSlots": 5,
                  "commandPermissionLevel": 3,
                  "autoGenHealthMultiplier": -1,
                  "ignoredFoodMods": ["vinery"]
                }
                """);

        assertTrue(FlorafareConfig.reloadServerSettings());

        assertEquals(5, FlorafareConfig.maxBuffSlots);
        assertEquals(3, FlorafareConfig.commandPermissionLevel);
        assertEquals(0.0, FlorafareConfig.autoGenHealthMultiplier, "clamped like a startup load");
        assertTrue(FoodExclusions.canonicalEntries(FoodExclusions.Effect.EXCLUDE).contains("vinery:*"));
    }

    @Test
    @DisplayName("an unparseable file on /reload keeps the settings in force instead of resetting them")
    void reloadKeepsSettingsOnBrokenFile() throws Exception {
        FlorafareConfig.maxBuffSlots = 4;
        FlorafareConfig.ignoredFoodMods = new ArrayList<>(List.of("vinery"));
        FlorafareConfig.applyExclusions();
        String broken = "{ \"maxBuffSlots\": 1, ";
        Files.writeString(configDir.resolve("florafare.json"), broken);

        assertFalse(FlorafareConfig.reloadServerSettings());

        assertEquals(4, FlorafareConfig.maxBuffSlots);
        assertEquals(List.of("vinery"), FlorafareConfig.ignoredFoodMods);
        assertEquals(broken, Files.readString(configDir.resolve("florafare.json")),
                "a reload never writes the file");
    }

    @Test
    @DisplayName("the auto-generation clamps shared with config_generation.json")
    void autoGenClamps() {
        assertEquals(0, FlorafareConfig.clampAutoGenDuration(-5));
        assertEquals(1200, FlorafareConfig.clampAutoGenDuration(1200));
        assertEquals(FlorafareConfig.MAX_AUTO_GEN_DURATION_MULT,
                FlorafareConfig.clampAutoGenDuration(Integer.MAX_VALUE));
        assertEquals(0.0, FlorafareConfig.clampAutoGenHealth(-1));
        assertEquals(0.5, FlorafareConfig.clampAutoGenHealth(Double.NaN));
        assertEquals(FlorafareConfig.MAX_AUTO_GEN_HEALTH_MULT, FlorafareConfig.clampAutoGenHealth(1e9));
    }

    @Test
    @DisplayName("the natural-regeneration settings survive a restart, are clamped and re-read on /reload")
    void regenSettings() throws Exception {
        FlorafareConfig.regenMode = FlorafareConfig.RegenMode.CURVE;
        FlorafareConfig.regenDelayAfterDamage = 5f;
        FlorafareConfig.exhaustionMultiplier = 0.5f;
        FlorafareConfig.regenMinHunger = 10;
        FlorafareConfig.save();
        assertTrue(Files.readString(configDir.resolve("florafare.json")).contains("\"regenMode\": \"curve\""),
                "written as the lowercase name the README documents");
        FlorafareConfig.regenMode = FlorafareConfig.RegenMode.VANILLA;
        FlorafareConfig.regenDelayAfterDamage = 2f;
        FlorafareConfig.exhaustionMultiplier = 1f;
        FlorafareConfig.regenMinHunger = 6;
        restart();
        assertEquals(FlorafareConfig.RegenMode.CURVE, FlorafareConfig.regenMode);
        assertEquals(5f, FlorafareConfig.regenDelayAfterDamage);
        assertEquals(0.5f, FlorafareConfig.exhaustionMultiplier);
        assertEquals(10, FlorafareConfig.regenMinHunger);

        Files.writeString(configDir.resolve("florafare.json"), """
                { "regenMode": "sometimes", "regenDelayAfterDamage": -4, "exhaustionMultiplier": 99,
                  "regenMinHunger": 25, "regenHungerCurve": 3, "regenBase": -1 }
                """);
        restart();
        assertEquals(FlorafareConfig.RegenMode.VANILLA, FlorafareConfig.regenMode, "unknown mode: vanilla");
        assertEquals(0f, FlorafareConfig.regenDelayAfterDamage);
        assertEquals(FlorafareConfig.MAX_EXHAUSTION_MULTIPLIER, FlorafareConfig.exhaustionMultiplier);
        assertEquals(20, FlorafareConfig.regenMinHunger);
        assertEquals(1f, FlorafareConfig.regenHungerCurve);
        assertEquals(0f, FlorafareConfig.regenBase);

        Files.writeString(configDir.resolve("florafare.json"), "{ \"regenMode\": \"CURVE\", \"regenDelayAfterDamage\": 3.5 }");
        assertTrue(FlorafareConfig.reloadServerSettings());
        assertEquals(FlorafareConfig.RegenMode.CURVE, FlorafareConfig.regenMode);
        assertEquals(3.5f, FlorafareConfig.regenDelayAfterDamage);
        assertEquals(6, FlorafareConfig.regenMinHunger, "missing keys go back to the default");

        FlorafareConfig.regenMode = FlorafareConfig.RegenMode.VANILLA;
        FlorafareConfig.regenDelayAfterDamage = 2f;
        FlorafareConfig.exhaustionMultiplier = 1f;
        FlorafareConfig.regenHungerCurve = 0.25f;
        FlorafareConfig.regenBase = 0.5f;
    }

    @Test
    @DisplayName("the write permission level and setbuff retention survive a restart, clamped")
    void writeLevelAndRetention() throws Exception {
        FlorafareConfig.writeCommandPermissionLevel = 4;
        FlorafareConfig.runtimeBuffRetentionDays = 30;
        FlorafareConfig.save();
        FlorafareConfig.writeCommandPermissionLevel = 3;
        FlorafareConfig.runtimeBuffRetentionDays = 180;
        restart();
        assertEquals(4, FlorafareConfig.writeCommandPermissionLevel);
        assertEquals(30, FlorafareConfig.runtimeBuffRetentionDays);

        Files.writeString(configDir.resolve("florafare.json"),
                "{ \"writeCommandPermissionLevel\": 9, \"runtimeBuffRetentionDays\": -3 }");
        restart();
        assertEquals(4, FlorafareConfig.writeCommandPermissionLevel, "clamped into 0-4");
        assertEquals(0, FlorafareConfig.runtimeBuffRetentionDays, "negative means keep forever");

        // A /reload re-reads it, like commandPermissionLevel.
        Files.writeString(configDir.resolve("florafare.json"), "{ \"writeCommandPermissionLevel\": 2 }");
        assertTrue(FlorafareConfig.reloadServerSettings());
        assertEquals(2, FlorafareConfig.writeCommandPermissionLevel);

        FlorafareConfig.writeCommandPermissionLevel = 3;
        FlorafareConfig.runtimeBuffRetentionDays = 180;
    }

    @Test
    @DisplayName("saves racing an exclusion edit on two threads leave one whole file and no temp files")
    void concurrentSaves() throws Exception {
        FlorafareConfig.ignoredFoodItems = new ArrayList<>();
        int rounds = 200;
        Thread hud = new Thread(() -> {
            for (int i = 0; i < rounds; i++) FlorafareConfig.save();
        });
        Thread server = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                int n = i;
                FlorafareConfig.editExclusionLists(() -> FlorafareConfig.ignoredFoodItems.add("mod:item_" + n));
                FlorafareConfig.save();
            }
        });
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        hud.setUncaughtExceptionHandler((t, e) -> failures.add(e));
        server.setUncaughtExceptionHandler((t, e) -> failures.add(e));
        hud.start();
        server.start();
        hud.join();
        server.join();

        assertTrue(failures.isEmpty(), "no save may fail mid-edit: " + failures);
        try (var files = Files.list(configDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "every temp file is moved or deleted");
        }
        restart();
        assertEquals(rounds, FlorafareConfig.ignoredFoodItems.size(), "the last save holds every edit");
        FlorafareConfig.ignoredFoodItems = new ArrayList<>();
    }
}
