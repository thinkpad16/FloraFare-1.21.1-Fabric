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

    // Головний перемикач синергій
    public static boolean enableSynergies = true;

    public static void load() {
        if (FILE.exists()) {
            try (FileReader reader = new FileReader(FILE)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) {
                    enableSynergies = data.enableSynergies;
                }
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to load config", e);
            }
        } else {
            save();
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(FILE)) {
            ConfigData data = new ConfigData();
            data.enableSynergies = enableSynergies;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save config", e);
        }
    }

    private static class ConfigData {
        public boolean enableSynergies = true;
    }
}