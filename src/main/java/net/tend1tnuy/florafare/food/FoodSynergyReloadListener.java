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
                    if (obj.has("entries") && obj.get("entries").isJsonArray()) {
                        for (JsonElement element : obj.getAsJsonArray("entries")) {
                            parseAndRegister(element.getAsJsonObject(), id);
                        }
                    } else {
                        parseAndRegister(obj, id);
                    }
                }
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to parse food synergy file: {}", id, e);
            }
        });

        Florafare.LOGGER.info("Loaded {} secret food synergies!", FoodSynergyManager.getAllSynergies().size());
    }

    private void parseAndRegister(JsonObject json, Identifier fileId) {
        if (!json.has("id")) {
            Florafare.LOGGER.error(
                    "Error in file {}: Missing 'id' field! Synergy skipped.", fileId);
            return;
        }
        String id = json.get("id").getAsString();

        // A synergy with no requirements is satisfied by everything, so it would switch
        // on the moment it loaded and never switch off — and with no requirement to read
        // a remaining duration from, it took Integer.MAX_VALUE ticks and became
        // permanent. Rejected here rather than left for the component to guard against.
        if (!json.has("requirements") || !json.get("requirements").isJsonArray()
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
            // Normalize so requirements match the canonical food target keys
            // (bare item ids / "#" tag ids).
            requirements.add(FoodBuffManager.normalizeTarget(re.getAsString()));
        }

        int duration = json.has("duration") ? json.get("duration").getAsInt() : 2400;
        double healthBonus = json.has("health_bonus") ? json.get("health_bonus").getAsDouble() : 0.0;

        // Both lists are validated exactly the way FoodReloadListener validates a food
        // buff's. They used to be parsed with Identifier.of and no registry check at all,
        // which failed twice over: a malformed id threw out of here and the outer catch
        // discarded the WHOLE file under a generic "failed to parse" line, and a
        // well-formed id for an effect that does not exist was accepted silently, then
        // dropped without a word by the ifPresent in PlayerFoodComponent#applyEffect.
        // Either way a typo cost a synergy its effects with nothing in the log naming it.
        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (json.has("effects")) {
            for (JsonElement e : json.getAsJsonArray("effects")) {
                JsonObject effObj = e.getAsJsonObject();
                String rawId = effObj.get("id").getAsString();
                Identifier effId = Identifier.tryParse(rawId);
                if (effId == null
                        || !net.minecraft.registry.Registries.STATUS_EFFECT.containsId(effId)) {
                    Florafare.LOGGER.error(
                            "Error in file {}: synergy '{}' lists effect '{}', which does not exist! "
                                    + "Effect skipped.", fileId, id, rawId);
                    continue;
                }
                effects.add(new FoodBuffData.EffectData(
                        effId,
                        effObj.get("duration").getAsInt(),
                        effObj.has("amplifier") ? effObj.get("amplifier").getAsInt() : 0
                ));
            }
        }

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (json.has("attributes")) {
            for (JsonElement e : json.getAsJsonArray("attributes")) {
                JsonObject attrObj = e.getAsJsonObject();
                String rawId = attrObj.get("attribute").getAsString();
                Identifier attrId = Identifier.tryParse(rawId);
                if (attrId == null
                        || !net.minecraft.registry.Registries.ATTRIBUTE.containsId(attrId)) {
                    Florafare.LOGGER.error(
                            "Error in file {}: synergy '{}' lists attribute '{}', which does not "
                                    + "exist! Attribute skipped.", fileId, id, rawId);
                    continue;
                }
                attributes.add(new FoodBuffData.AttributeData(
                        attrId,
                        attrObj.get("amount").getAsDouble(),
                        attrObj.get("operation").getAsString()
                ));
            }
        }

        FoodSynergyData synergy = new FoodSynergyData(id, requirements, duration, healthBonus, effects, attributes);
        FoodSynergyManager.putSynergy(synergy);
    }
}