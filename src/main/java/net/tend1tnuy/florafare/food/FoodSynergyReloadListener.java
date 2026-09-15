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

public class FoodSynergyReloadListener extends JsonDataLoader implements IdentifiableResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public FoodSynergyReloadListener() {
        super(GSON, "food_synergies");
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of(Florafare.MOD_ID, "food_synergies_loader");
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared, ResourceManager manager, Profiler profiler) {
        FoodSynergyManager.clear();

        // Same reason as in FoodReloadListener: the cached client payload is built from
        // this map and has to be dropped whenever the map is rebuilt, world load included.
        Florafare.invalidateSyncPayloads();

        prepared.forEach((id, jsonElement) -> {
            try {
                if (jsonElement.isJsonObject()) {
                    JsonObject obj = jsonElement.getAsJsonObject();
                    if (JsonFields.hasArray(obj, "entries")) {
                        for (JsonElement element : obj.getAsJsonArray("entries")) {
                            parseEntry(element, id);
                        }
                    } else {
                        parseEntry(obj, id);
                    }
                }
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to parse food synergy file: {}", id, e);
            }
        });

        Florafare.LOGGER.info("Loaded {} secret food synergies!", FoodSynergyManager.getAllSynergies().size());
    }

    /**
     * Parses one synergy, keeping its failure to itself.
     *
     * <p>Same reason as in {@code FoodReloadListener}: the only {@code catch} was around
     * the whole file, so one bad entry discarded every synergy after it and the log named
     * only the file.
     */
    private void parseEntry(JsonElement element, Identifier fileId) {
        if (element == null || !element.isJsonObject()) {
            Florafare.LOGGER.error(
                    "Error in file {}: a synergy entry is not a JSON object! Entry skipped.", fileId);
            return;
        }
        JsonObject json = element.getAsJsonObject();
        try {
            parseAndRegister(json, fileId);
        } catch (Exception e) {
            Florafare.LOGGER.error(
                    "Error in file {}: synergy '{}' could not be parsed and was skipped. "
                            + "The rest of the file is unaffected.",
                    fileId, JsonFields.getString(json, "id", "<no id>"), e);
        }
    }

    private void parseAndRegister(JsonObject json, Identifier fileId) {
        String id = JsonFields.getString(json, "id");
        if (id == null) {
            Florafare.LOGGER.error(
                    "Error in file {}: Missing or non-string 'id' field! Synergy skipped.", fileId);
            return;
        }

        // A synergy with no requirements is satisfied by everything, so it would switch
        // on the moment it loaded and never switch off — and with no requirement to read
        // a remaining duration from, it took Integer.MAX_VALUE ticks and became
        // permanent. Rejected here rather than left for the component to guard against.
        if (!JsonFields.hasArray(json, "requirements")
                || json.getAsJsonArray("requirements").isEmpty()) {
            Florafare.LOGGER.error(
                    "Error in file {}: Synergy '{}' has no 'requirements' array! "
                            + "A synergy with no requirements would be permanently active. Skipped.",
                    fileId, id);
            return;
        }

        List<String> requirements = new ArrayList<>();
        JsonArray reqArray = json.getAsJsonArray("requirements");
        for (JsonElement re : reqArray) {
            if (!re.isJsonPrimitive()) {
                Florafare.LOGGER.error(
                        "Error in file {}: synergy '{}' has a requirement that is not a string! "
                                + "Requirement skipped.", fileId, id);
                continue;
            }
            // Normalize so requirements match the canonical food target keys
            // (bare item ids / "#" tag ids).
            requirements.add(FoodBuffManager.normalizeTarget(re.getAsString()));
        }
        // Every requirement could have been thrown out above, which puts us back at the
        // "satisfied by everything, permanently active" case the check further up exists
        // to prevent.
        if (requirements.isEmpty()) {
            Florafare.LOGGER.error(
                    "Error in file {}: synergy '{}' has no usable requirements left. Skipped.",
                    fileId, id);
            return;
        }

        int duration = JsonFields.getInt(json, "duration", 2400);
        double healthBonus = JsonFields.getDouble(json, "health_bonus", 0.0);

        // Both lists are validated exactly the way FoodReloadListener validates a food
        // buff's. They used to be parsed with Identifier.of and no registry check at all,
        // which failed twice over: a malformed id threw out of here and the outer catch
        // discarded the WHOLE file under a generic "failed to parse" line, and a
        // well-formed id for an effect that does not exist was accepted silently, then
        // dropped without a word by the ifPresent in PlayerFoodComponent#applyEffect.
        // Either way a typo cost a synergy its effects with nothing in the log naming it.
        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (JsonFields.hasArray(json, "effects")) {
            for (JsonElement e : json.getAsJsonArray("effects")) {
                if (!e.isJsonObject()) continue;
                JsonObject effObj = e.getAsJsonObject();
                String rawId = JsonFields.getString(effObj, "id");
                Identifier effId = rawId == null ? null : Identifier.tryParse(rawId);
                if (effId == null
                        || !net.minecraft.registry.Registries.STATUS_EFFECT.containsId(effId)) {
                    Florafare.LOGGER.error(
                            "Error in file {}: synergy '{}' lists effect '{}', which does not exist! "
                                    + "Effect skipped.", fileId, id, rawId);
                    continue;
                }
                // Defaults to the synergy's own duration — which is also what the
                // component rewrites it to when the synergy activates, so an omitted
                // value here was never going to mean anything else.
                effects.add(new FoodBuffData.EffectData(
                        effId,
                        JsonFields.getInt(effObj, "duration", duration),
                        JsonFields.getInt(effObj, "amplifier", 0)
                ));
            }
        }

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (JsonFields.hasArray(json, "attributes")) {
            for (JsonElement e : json.getAsJsonArray("attributes")) {
                if (!e.isJsonObject()) continue;
                JsonObject attrObj = e.getAsJsonObject();
                String rawId = JsonFields.getString(attrObj, "attribute");
                Identifier attrId = rawId == null ? null : Identifier.tryParse(rawId);
                if (attrId == null
                        || !net.minecraft.registry.Registries.ATTRIBUTE.containsId(attrId)) {
                    Florafare.LOGGER.error(
                            "Error in file {}: synergy '{}' lists attribute '{}', which does not "
                                    + "exist! Attribute skipped.", fileId, id, rawId);
                    continue;
                }
                attributes.add(new FoodBuffData.AttributeData(
                        attrId,
                        JsonFields.getDouble(attrObj, "amount", 0.0),
                        JsonFields.getString(attrObj, "operation", "add_value")
                ));
            }
        }

        FoodSynergyData synergy = new FoodSynergyData(id, requirements, duration, healthBonus, effects, attributes);
        FoodSynergyManager.putSynergy(synergy);
    }
}