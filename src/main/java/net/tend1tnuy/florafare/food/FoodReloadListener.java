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

public class FoodReloadListener extends JsonDataLoader implements IdentifiableResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public FoodReloadListener() {
        super(GSON, "food_buffs"); // Шукає у папці data/*/food_buffs/
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of(Florafare.MOD_ID, "food_buffs_loader");
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared, ResourceManager manager, Profiler profiler) {
        FoodBuffManager.clear();

        prepared.forEach((id, jsonElement) -> {
            try {
                JsonObject json = jsonElement.getAsJsonObject();

                // --- ФІНАЛ: Перевірка на файл глобальних налаштувань автогенерації ---
                // Якщо назва файлу config_generation.json, ми оновлюємо змінні замість створення їжі
                if (id.getPath().equals("config_generation")) {
                    if (json.has("duration_multiplier")) {
                        FoodBuffManager.AUTO_GEN_DURATION_MULT = json.get("duration_multiplier").getAsInt();
                    }
                    if (json.has("health_multiplier")) {
                        FoodBuffManager.AUTO_GEN_HEALTH_MULT = json.get("health_multiplier").getAsDouble();
                    }
                    Florafare.LOGGER.info("Loaded global auto-generation config!");
                    return; // Пропускаємо створення звичайного бафу і йдемо до наступного файлу
                }

                // --- Парсинг звичайної їжі ---
                String target = json.has("target") ? json.get("target").getAsString() : "item:" + id.toString().replace("/", ":");
                int duration = json.has("duration") ? json.get("duration").getAsInt() : 6000;
                int nutrition = json.has("nutrition") ? json.get("nutrition").getAsInt() : 0;
                float saturation = json.has("saturation") ? json.get("saturation").getAsFloat() : 0.0f;
                double healthBonus = json.has("health_bonus") ? json.get("health_bonus").getAsDouble() : 0.0;

                // Парсинг ефектів (Potion Effects)
                List<FoodBuffData.EffectData> effects = new ArrayList<>();
                if (json.has("effects")) {
                    JsonArray effArray = json.getAsJsonArray("effects");
                    for (JsonElement e : effArray) {
                        JsonObject effObj = e.getAsJsonObject();
                        effects.add(new FoodBuffData.EffectData(
                                Identifier.of(effObj.get("id").getAsString()),
                                effObj.get("duration").getAsInt(),
                                effObj.has("amplifier") ? effObj.get("amplifier").getAsInt() : 0
                        ));
                    }
                }

                // Парсинг атрибутів (Attribute Modifiers)
                List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
                if (json.has("attributes")) {
                    JsonArray attrArray = json.getAsJsonArray("attributes");
                    for (JsonElement e : attrArray) {
                        JsonObject attrObj = e.getAsJsonObject();
                        attributes.add(new FoodBuffData.AttributeData(
                                Identifier.of(attrObj.get("attribute").getAsString()),
                                attrObj.get("amount").getAsDouble(),
                                attrObj.get("operation").getAsString()
                        ));
                    }
                }

                FoodBuffData data = new FoodBuffData(target, duration, nutrition, saturation, healthBonus, effects, attributes);
                FoodBuffManager.putConfig(target, data);

            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to parse food buff datapack file: {}", id, e);
            }
        });

        Florafare.LOGGER.info("Loaded {} Valheim food configurations!", FoodBuffManager.getConfigCount());
    }
}