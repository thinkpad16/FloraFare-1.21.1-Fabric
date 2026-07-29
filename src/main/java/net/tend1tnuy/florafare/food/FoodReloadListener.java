package net.tend1tnuy.florafare.food;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import net.tend1tnuy.florafare.Florafare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the loading and parsing of food buff configurations from datapack JSON files.
 */
public class FoodReloadListener extends JsonDataLoader implements IdentifiableResourceReloadListener {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String CONFIG_GEN_KEY   = "config_generation";
    private static final String KEY_DURATION     = "duration";
    private static final String KEY_NUTRITION    = "nutrition";
    private static final String KEY_SATURATION   = "saturation";
    private static final String KEY_HEALTH_BONUS = "health_bonus";
    private static final String KEY_EFFECTS      = "effects";
    private static final String KEY_ATTRIBUTES   = "attributes";
    private static final String KEY_ENTRIES      = "entries";
    private static final String KEY_ID           = "id";
    private static final String KEY_AMPLIFIER    = "amplifier";
    private static final String KEY_ATTRIBUTE    = "attribute";
    private static final String KEY_AMOUNT       = "amount";
    private static final String KEY_OPERATION    = "operation";
    private static final String KEY_PRIORITY     = "priority";
    private static final String KEY_ALWAYS_EDIBLE = "always_edible";

    /** Entries whose target belongs to a mod that is not installed (counted per reload). */
    private int absentModTargets;

    public FoodReloadListener() {
        super(GSON, "food_buffs");
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of(Florafare.MOD_ID, "food_buffs_loader");
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared,
                         ResourceManager manager, Profiler profiler) {
        FoodBuffManager.clear();
        absentModTargets = 0;

        prepared.forEach((id, jsonElement) -> {
            try {
                // Global auto-generation settings file.
                if (id.getPath().equals(CONFIG_GEN_KEY)) {
                    JsonObject json = jsonElement.getAsJsonObject();
                    if (json.has("duration_multiplier")) {
                        FoodBuffManager.AUTO_GEN_DURATION_MULT =
                                json.get("duration_multiplier").getAsInt();
                    }
                    if (json.has("health_multiplier")) {
                        FoodBuffManager.AUTO_GEN_HEALTH_MULT =
                                json.get("health_multiplier").getAsDouble();
                    }
                    Florafare.LOGGER.info("Loaded global auto-generation config!");
                    return;
                }

                // Legacy JSON Array format (priority defaults to 0).
                if (jsonElement.isJsonArray()) {
                    JsonArray array = jsonElement.getAsJsonArray();
                    for (JsonElement element : array) {
                        parseAndRegister(element.getAsJsonObject(), null, id, 0, false);
                    }
                } else if (jsonElement.isJsonObject()) {
                    JsonObject obj = jsonElement.getAsJsonObject();

                    // Зчитуємо глобальні параметри для всього файлу
                    int filePriority = obj.has(KEY_PRIORITY)
                            ? obj.get(KEY_PRIORITY).getAsInt() : 0;
                    boolean fileAlwaysEdible = obj.has(KEY_ALWAYS_EDIBLE)
                            && obj.get(KEY_ALWAYS_EDIBLE).getAsBoolean();

                    if (obj.has(KEY_ENTRIES) && obj.get(KEY_ENTRIES).isJsonArray()) {
                        // Standard multi-entry format.
                        for (JsonElement element : obj.getAsJsonArray(KEY_ENTRIES)) {
                            parseAndRegister(element.getAsJsonObject(), null, id, filePriority, fileAlwaysEdible);
                        }
                    } else if (obj.has(KEY_ID)) {
                        // Single-entry object at the root.
                        parseAndRegister(obj, id.toString().replace("/", ":"), id, filePriority, fileAlwaysEdible);
                    }
                }

            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to parse food buff datapack file: {}", id, e);
            }
        });

        Florafare.LOGGER.info("Loaded {} food configurations!", FoodBuffManager.getConfigCount());
        if (absentModTargets > 0) {
            Florafare.LOGGER.info(
                    "{} entries target items from mods that are not installed; "
                            + "they stay inactive until those mods are present.",
                    absentModTargets);
        }
    }

    private void parseAndRegister(JsonObject json, String defaultTargetFallback,
                                  Identifier fileId, int filePriority, boolean fileAlwaysEdible) {
        if (!json.has(KEY_ID) && defaultTargetFallback == null) {
            Florafare.LOGGER.error(
                    "Error in file {}: Missing 'id' field! Buff skipped.", fileId);
            return;
        }

        String rawTarget = json.has(KEY_ID)
                ? json.get(KEY_ID).getAsString() : defaultTargetFallback;
        String target = FoodBuffManager.normalizeTarget(rawTarget);

        // Validate plain item-id targets.
        boolean isPlainItemTarget = !target.startsWith("#")
                && !target.startsWith("namespace:")
                && !target.startsWith("template:")
                && !target.startsWith("potion:");
        if (isPlainItemTarget) {
            Identifier targetId = Identifier.tryParse(target);
            if (targetId == null) {
                Florafare.LOGGER.warn(
                        "Warning in file {}: '{}' is not a valid item id! "
                                + "Buff may never trigger.", fileId, target);
            } else if (!net.minecraft.registry.Registries.ITEM.containsId(targetId)) {
                if (net.fabricmc.loader.api.FabricLoader.getInstance()
                        .isModLoaded(targetId.getNamespace())) {
                    Florafare.LOGGER.warn(
                            "Warning in file {}: Item '{}' does not exist! "
                                    + "Buff may never trigger.", fileId, target);
                } else {
                    absentModTargets++;
                }
            }
        }

        int    duration    = json.has(KEY_DURATION)    ? json.get(KEY_DURATION).getAsInt()    : 6000;
        int    nutrition   = json.has(KEY_NUTRITION)   ? json.get(KEY_NUTRITION).getAsInt()   : 0;
        float  saturation  = json.has(KEY_SATURATION)  ? json.get(KEY_SATURATION).getAsFloat() : 0.0f;
        double healthBonus = json.has(KEY_HEALTH_BONUS) ? json.get(KEY_HEALTH_BONUS).getAsDouble() : 0.0;
        int    priority    = json.has(KEY_PRIORITY)    ? json.get(KEY_PRIORITY).getAsInt()    : filePriority;

        // Якщо параметр вказаний всередині елемента — використовуємо його. Якщо ні — використовуємо глобальний (з кореня)
        boolean alwaysEdible = json.has(KEY_ALWAYS_EDIBLE)
                ? json.get(KEY_ALWAYS_EDIBLE).getAsBoolean() : fileAlwaysEdible;

        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (json.has(KEY_EFFECTS)) {
            for (JsonElement e : json.getAsJsonArray(KEY_EFFECTS)) {
                JsonObject effObj = e.getAsJsonObject();
                Identifier effId  = Identifier.tryParse(effObj.get(KEY_ID).getAsString());
                if (effId != null
                        && net.minecraft.registry.Registries.STATUS_EFFECT.containsId(effId)) {
                    effects.add(new FoodBuffData.EffectData(
                            effId,
                            effObj.get(KEY_DURATION).getAsInt(),
                            effObj.has(KEY_AMPLIFIER) ? effObj.get(KEY_AMPLIFIER).getAsInt() : 0));
                } else {
                    Florafare.LOGGER.error(
                            "Error in file {}: Effect '{}' does not exist! Effect skipped.",
                            fileId, effObj.get(KEY_ID).getAsString());
                }
            }
        }

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (json.has(KEY_ATTRIBUTES)) {
            for (JsonElement e : json.getAsJsonArray(KEY_ATTRIBUTES)) {
                JsonObject attrObj = e.getAsJsonObject();
                Identifier attrId  = Identifier.tryParse(attrObj.get(KEY_ATTRIBUTE).getAsString());
                if (attrId != null
                        && net.minecraft.registry.Registries.ATTRIBUTE.containsId(attrId)) {
                    attributes.add(new FoodBuffData.AttributeData(
                            attrId,
                            attrObj.get(KEY_AMOUNT).getAsDouble(),
                            attrObj.get(KEY_OPERATION).getAsString()));
                } else {
                    Florafare.LOGGER.error(
                            "Error in file {}: Attribute '{}' does not exist! Attribute skipped.",
                            fileId, attrObj.get(KEY_ATTRIBUTE).getAsString());
                }
            }
        }

        FoodBuffData data = new FoodBuffData(
                target, duration, nutrition, saturation,
                healthBonus, effects, attributes, priority, alwaysEdible);
        FoodBuffManager.putConfig(target, data);
    }
}