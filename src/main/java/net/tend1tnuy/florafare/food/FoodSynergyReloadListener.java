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
        String id = json.get("id").getAsString();

        List<String> requirements = new ArrayList<>();
        JsonArray reqArray = json.getAsJsonArray("requirements");
        for (JsonElement re : reqArray) {
            requirements.add(re.getAsString());
        }

        int duration = json.has("duration") ? json.get("duration").getAsInt() : 2400;
        double healthBonus = json.has("health_bonus") ? json.get("health_bonus").getAsDouble() : 0.0;

        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (json.has("effects")) {
            for (JsonElement e : json.getAsJsonArray("effects")) {
                JsonObject effObj = e.getAsJsonObject();
                effects.add(new FoodBuffData.EffectData(
                        Identifier.of(effObj.get("id").getAsString()),
                        effObj.get("duration").getAsInt(),
                        effObj.has("amplifier") ? effObj.get("amplifier").getAsInt() : 0
                ));
            }
        }

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (json.has("attributes")) {
            for (JsonElement e : json.getAsJsonArray("attributes")) {
                JsonObject attrObj = e.getAsJsonObject();
                attributes.add(new FoodBuffData.AttributeData(
                        Identifier.of(attrObj.get("attribute").getAsString()),
                        attrObj.get("amount").getAsDouble(),
                        attrObj.get("operation").getAsString()
                ));
            }
        }

        FoodSynergyData synergy = new FoodSynergyData(id, requirements, duration, healthBonus, effects, attributes);
        FoodSynergyManager.putSynergy(synergy);
    }
}