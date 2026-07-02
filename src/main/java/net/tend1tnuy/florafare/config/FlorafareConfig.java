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

    // Main settings
    public static boolean enableSynergies = true;
    public static LogLevel consumptionLogging = LogLevel.ALL; // Default set to ALL

    // Client HUD settings (stored as enum names; parsed by the client HudConfig)
    public static String hudLayout = "COMPACT";
    public static String hudIconSize = "LARGE";
    public static String hudPosition = "BOTTOM_LEFT";

    public static void load() {
        if (FILE.exists()) {
            try (FileReader reader = new FileReader(FILE)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) {
                    enableSynergies = data.enableSynergies;
                    if (data.consumptionLogging != null) {
                        consumptionLogging = data.consumptionLogging;
                    }
                    if (data.hudLayout != null) hudLayout = data.hudLayout;
                    if (data.hudIconSize != null) hudIconSize = data.hudIconSize;
                    if (data.hudPosition != null) hudPosition = data.hudPosition;
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
            data.hudLayout = hudLayout;
            data.hudIconSize = hudIconSize;
            data.hudPosition = hudPosition;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save config", e);
        }
    }

    private static class ConfigData {
        public boolean enableSynergies = true;
        public LogLevel consumptionLogging = LogLevel.ALL;
        public String hudLayout = "COMPACT";
        public String hudIconSize = "LARGE";
        public String hudPosition = "BOTTOM_LEFT";
    }
}