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

        // The serialized client payload is built from this map, so it is stale the
        // moment the map is rebuilt. Done here rather than only in END_DATA_PACK_RELOAD
        // because that event covers /reload alone — see Florafare#invalidateSyncPayloads.
        Florafare.invalidateSyncPayloads();

        // Seed from the global config default; a datapack's own config_generation.json
        // (below) can still override it for this reload. Also fixes these statics
        // staying stale across /reload once no datapack defines the file anymore.
        FoodBuffManager.AUTO_GEN_DURATION_MULT =
                net.tend1tnuy.florafare.config.FlorafareConfig.autoGenDurationMultiplier;
        FoodBuffManager.AUTO_GEN_HEALTH_MULT =
                net.tend1tnuy.florafare.config.FlorafareConfig.autoGenHealthMultiplier;

        prepared.forEach((id, jsonElement) -> {
            try {
                // Global auto-generation settings file.
                if (id.getPath().equals(CONFIG_GEN_KEY)) {
                    JsonObject json = jsonElement.getAsJsonObject();
                    FoodBuffManager.AUTO_GEN_DURATION_MULT = JsonFields.getInt(
                            json, "duration_multiplier", FoodBuffManager.AUTO_GEN_DURATION_MULT);
                    FoodBuffManager.AUTO_GEN_HEALTH_MULT = JsonFields.getDouble(
                            json, "health_multiplier", FoodBuffManager.AUTO_GEN_HEALTH_MULT);
                    Florafare.LOGGER.info("Loaded global auto-generation config!");
                    return;
                }

                // Legacy JSON Array format (priority defaults to 0).
                if (jsonElement.isJsonArray()) {
                    JsonArray array = jsonElement.getAsJsonArray();
                    for (JsonElement element : array) {
                        parseEntry(element, null, id, 0, false);
                    }
                } else if (jsonElement.isJsonObject()) {
                    JsonObject obj = jsonElement.getAsJsonObject();

                    // Through JsonFields like every other read. These two are the
                    // file-level defaults, so a bare getAsInt/getAsBoolean on a
                    // misspelled value ("priority": "high") threw out to the catch
                    // below and discarded the whole file — which is the exact failure
                    // JsonFields exists to stop, missed here because these are read
                    // before the per-entry parsing starts.
                    int filePriority = JsonFields.getInt(obj, KEY_PRIORITY, 0);
                    boolean fileAlwaysEdible =
                            JsonFields.getBoolean(obj, KEY_ALWAYS_EDIBLE, false);

                    if (obj.has(KEY_ENTRIES) && obj.get(KEY_ENTRIES).isJsonArray()) {
                        // Standard multi-entry format.
                        for (JsonElement element : obj.getAsJsonArray(KEY_ENTRIES)) {
                            parseEntry(element, null, id, filePriority, fileAlwaysEdible);
                        }
                    } else if (obj.has(KEY_ID)) {
                        // Single-entry object at the root.
                        parseEntry(obj, defaultTargetFor(id), id, filePriority, fileAlwaysEdible);
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

    /**
     * The implicit target for a file that holds a single entry with no {@code id}.
     *
     * <p>Only the file NAME can stand in for an item id, and only its last path segment:
     * a resource id like {@code mypack:meats/beef} used to be turned into a target by
     * replacing every "/" with ":", which produced {@code mypack:meats:beef} — two colons,
     * not a valid identifier, and a buff that could never match anything. The namespace
     * comes from the file's own, so {@code mypack:meats/beef.json} now means
     * {@code mypack:beef}, which is the item the author was plainly naming.
     */
    static String defaultTargetFor(Identifier fileId) {
        String path = fileId.getPath();
        int lastSlash = path.lastIndexOf('/');
        String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return fileId.getNamespace() + ":" + name;
    }

    /**
     * Parses one entry, keeping its failure to itself.
     *
     * <p>The only {@code catch} used to be around the whole file, so one malformed entry
     * took every entry after it down with it — silently, since the log line named the
     * file and not the entry. A pack author saw "Failed to parse food buff datapack file"
     * and a category of foods that simply did nothing.
     */
    private void parseEntry(JsonElement element, String defaultTargetFallback,
                            Identifier fileId, int filePriority, boolean fileAlwaysEdible) {
        if (element == null || !element.isJsonObject()) {
            Florafare.LOGGER.error(
                    "Error in file {}: an entry is not a JSON object! Entry skipped.", fileId);
            return;
        }
        JsonObject json = element.getAsJsonObject();
        try {
            parseAndRegister(json, defaultTargetFallback, fileId, filePriority, fileAlwaysEdible);
        } catch (Exception e) {
            Florafare.LOGGER.error(
                    "Error in file {}: entry '{}' could not be parsed and was skipped. "
                            + "The rest of the file is unaffected.",
                    fileId, JsonFields.getString(json, KEY_ID, "<no id>"), e);
        }
    }

    private void parseAndRegister(JsonObject json, String defaultTargetFallback,
                                  Identifier fileId, int filePriority, boolean fileAlwaysEdible) {
        if (!json.has(KEY_ID) && defaultTargetFallback == null) {
            Florafare.LOGGER.error(
                    "Error in file {}: Missing 'id' field! Buff skipped.", fileId);
            return;
        }

        String rawTarget = JsonFields.getString(json, KEY_ID, defaultTargetFallback);
        if (rawTarget == null) {
            Florafare.LOGGER.error(
                    "Error in file {}: 'id' is present but not a string! Buff skipped.", fileId);
            return;
        }
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

        int    duration    = JsonFields.getInt(json, KEY_DURATION, 6000);
        int    nutrition   = JsonFields.getInt(json, KEY_NUTRITION, 0);
        float  saturation  = JsonFields.getFloat(json, KEY_SATURATION, 0.0f);
        double healthBonus = JsonFields.getDouble(json, KEY_HEALTH_BONUS, 0.0);
        int    priority    = JsonFields.getInt(json, KEY_PRIORITY, filePriority);

        boolean alwaysEdible = JsonFields.getBoolean(json, KEY_ALWAYS_EDIBLE, fileAlwaysEdible);

        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (JsonFields.hasArray(json, KEY_EFFECTS)) {
            for (JsonElement e : json.getAsJsonArray(KEY_EFFECTS)) {
                if (!e.isJsonObject()) continue;
                JsonObject effObj = e.getAsJsonObject();
                String     rawId  = JsonFields.getString(effObj, KEY_ID);
                Identifier effId  = rawId == null ? null : Identifier.tryParse(rawId);
                if (effId == null
                        || !net.minecraft.registry.Registries.STATUS_EFFECT.containsId(effId)) {
                    Florafare.LOGGER.error(
                            "Error in file {}: entry '{}' lists effect '{}', which does not exist! "
                                    + "Effect skipped.", fileId, target, rawId);
                    continue;
                }
                // Defaults to the buff's own duration. An effect is granted alongside the
                // buff that carries it, so "for as long as the buff" is the only sensible
                // reading of an omitted duration — and it beats throwing, which used to
                // discard every remaining entry in the file.
                effects.add(new FoodBuffData.EffectData(
                        effId,
                        JsonFields.getInt(effObj, KEY_DURATION, duration),
                        JsonFields.getInt(effObj, KEY_AMPLIFIER, 0)));
            }
        }

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (JsonFields.hasArray(json, KEY_ATTRIBUTES)) {
            for (JsonElement e : json.getAsJsonArray(KEY_ATTRIBUTES)) {
                if (!e.isJsonObject()) continue;
                JsonObject attrObj = e.getAsJsonObject();
                String     rawId   = JsonFields.getString(attrObj, KEY_ATTRIBUTE);
                Identifier attrId  = rawId == null ? null : Identifier.tryParse(rawId);
                if (attrId == null
                        || !net.minecraft.registry.Registries.ATTRIBUTE.containsId(attrId)) {
                    Florafare.LOGGER.error(
                            "Error in file {}: entry '{}' lists attribute '{}', which does not "
                                    + "exist! Attribute skipped.", fileId, target, rawId);
                    continue;
                }
                // "add_value" is both the commonest operation and the one mapOperation
                // already falls back to for anything it does not recognise, so defaulting
                // to it here changes no behaviour — it only stops the omission throwing.
                String operation = JsonFields.getString(attrObj, KEY_OPERATION, "add_value");
                attributes.add(new FoodBuffData.AttributeData(
                        attrId,
                        JsonFields.getDouble(attrObj, KEY_AMOUNT, 0.0),
                        operation));
            }
        }

        FoodBuffData data = new FoodBuffData(
                target, duration, nutrition, saturation,
                healthBonus, effects, attributes, priority, alwaysEdible);
        FoodBuffManager.putConfig(target, data);
    }
}