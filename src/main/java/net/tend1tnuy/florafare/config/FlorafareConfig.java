package net.tend1tnuy.florafare.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.tend1tnuy.florafare.Florafare;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class FlorafareConfig {
    private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "florafare.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Logging levels: NONE (disabled), REDUCED (less spam, only heals), ALL (everything)
    public enum LogLevel { NONE, REDUCED, ALL }

    // --- Main settings ---
    public static boolean enableSynergies = true;
    public static LogLevel consumptionLogging = LogLevel.ALL;

    // --- Server-authoritative gameplay settings (synced to clients on join/reload) ---
    public static int maxBuffSlots = 3;
    public static int autoGenDurationMultiplier = 1200;
    public static double autoGenHealthMultiplier = 0.5;
    public static boolean enableAlwaysEdibleOverride = true;
    public static boolean respectVanillaFoodEffects = false;
    public static boolean enableForgottenMead = true;
    public static boolean grantJournalOnFirstJoin = true;

    // --- Server-only settings (not synced; read at command registration time) ---
    public static int commandPermissionLevel = 2;

    /**
     * Item ids Florafare should never intercept, for interop with other food/hunger
     * mods that want to handle these items themselves (e.g. "modid:custom_stew").
     * Applied once at startup via FoodBuffManager.excludeItem; the resulting
     * exclusion set is what actually gets synced to clients, not this raw list.
     */
    public static java.util.List<String> ignoredFoodItems = new java.util.ArrayList<>();

    // --- Client-local cosmetic settings ---
    public static boolean enableDiscoveryToasts = true;
    public static int toastDisplayTimeMs = 5000;
    public static boolean stripFoodTooltips = true;

    // Client HUD settings (stored as enum names; parsed by the client HudConfig)
    public static String hudLayout = "COMPACT";
    public static String hudIconSize = "LARGE";
    public static String hudPosition = "BOTTOM_LEFT";
    public static float hudScale = 0.8f;

    public static void load() {
        if (FILE.exists()) {
            try (FileReader reader = new FileReader(FILE)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) {
                    enableSynergies = data.enableSynergies;
                    if (data.consumptionLogging != null) {
                        consumptionLogging = data.consumptionLogging;
                    }
                    maxBuffSlots = data.maxBuffSlots;
                    autoGenDurationMultiplier = data.autoGenDurationMultiplier;
                    autoGenHealthMultiplier = data.autoGenHealthMultiplier;
                    enableAlwaysEdibleOverride = data.enableAlwaysEdibleOverride;
                    respectVanillaFoodEffects = data.respectVanillaFoodEffects;
                    enableForgottenMead = data.enableForgottenMead;
                    grantJournalOnFirstJoin = data.grantJournalOnFirstJoin;
                    commandPermissionLevel = data.commandPermissionLevel;
                    if (data.ignoredFoodItems != null) ignoredFoodItems = data.ignoredFoodItems;
                    enableDiscoveryToasts = data.enableDiscoveryToasts;
                    toastDisplayTimeMs = data.toastDisplayTimeMs;
                    stripFoodTooltips = data.stripFoodTooltips;
                    if (data.hudLayout != null) hudLayout = data.hudLayout;
                    if (data.hudIconSize != null) hudIconSize = data.hudIconSize;
                    if (data.hudPosition != null) hudPosition = data.hudPosition;
                    hudScale = data.hudScale;
                }
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to load config", e);
            }
        }


        save();
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(FILE)) {
            ConfigData data = new ConfigData();
            data.enableSynergies = enableSynergies;
            data.consumptionLogging = consumptionLogging;
            data.maxBuffSlots = maxBuffSlots;
            data.autoGenDurationMultiplier = autoGenDurationMultiplier;
            data.autoGenHealthMultiplier = autoGenHealthMultiplier;
            data.enableAlwaysEdibleOverride = enableAlwaysEdibleOverride;
            data.respectVanillaFoodEffects = respectVanillaFoodEffects;
            data.enableForgottenMead = enableForgottenMead;
            data.grantJournalOnFirstJoin = grantJournalOnFirstJoin;
            data.commandPermissionLevel = commandPermissionLevel;
            data.ignoredFoodItems = ignoredFoodItems;
            data.enableDiscoveryToasts = enableDiscoveryToasts;
            data.toastDisplayTimeMs = toastDisplayTimeMs;
            data.stripFoodTooltips = stripFoodTooltips;
            data.hudLayout = hudLayout;
            data.hudIconSize = hudIconSize;
            data.hudPosition = hudPosition;
            data.hudScale = hudScale;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save config", e);
        }
    }

    private static class ConfigData {
        public boolean enableSynergies = true;
        public LogLevel consumptionLogging = LogLevel.ALL;
        public int maxBuffSlots = 3;
        public int autoGenDurationMultiplier = 1200;
        public double autoGenHealthMultiplier = 0.5;
        public boolean enableAlwaysEdibleOverride = true;
        public boolean respectVanillaFoodEffects = false;
        public boolean enableForgottenMead = true;
        public boolean grantJournalOnFirstJoin = true;
        public int commandPermissionLevel = 2;
        public java.util.List<String> ignoredFoodItems = new java.util.ArrayList<>();
        public boolean enableDiscoveryToasts = true;
        public int toastDisplayTimeMs = 5000;
        public boolean stripFoodTooltips = true;
        public String hudLayout = "COMPACT";
        public String hudIconSize = "LARGE";
        public String hudPosition = "BOTTOM_LEFT";
        public float hudScale = 0.8f;
    }
}
