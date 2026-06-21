package net.tend1tnuy.florafare.food;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.item.ForgottenMeadItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class FoodBuffManager {
    private static final Map<String, FoodBuffData> CONFIGS = new HashMap<>();

    public static void clear() {
        CONFIGS.clear();
    }

    public static void putConfig(String target, FoodBuffData data) {
        CONFIGS.put(target, data);
    }

    /**
     * Каскадна система пошуку конфігурації.
     * Пріоритет: Item -> Tag -> Namespace -> Template -> Auto Gen
     */
    public static FoodBuffData getConfig(ItemStack stack) {
        // Ігноруємо наш предмет для очищення (щоб він не генерував баф при споживанні)
        if (stack.getItem() instanceof ForgottenMeadItem) {
            return null;
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());

        // 1. Точний предмет
        String itemTarget = "item:" + itemId.toString();
        if (CONFIGS.containsKey(itemTarget)) return CONFIGS.get(itemTarget);

        // 2. Теги предмета
        for (TagKey<?> tag : stack.streamTags().toList()) {
            String tagTarget = "tag:" + tag.id().toString();
            if (CONFIGS.containsKey(tagTarget)) return CONFIGS.get(tagTarget);
        }

        // 3. Namespace (наприклад "namespace:farmersdelight")
        String nsTarget = "namespace:" + itemId.getNamespace();
        if (CONFIGS.containsKey(nsTarget)) return CONFIGS.get(nsTarget);

        // 4. Шаблон за замовчуванням
        if (CONFIGS.containsKey("template:default")) return CONFIGS.get("template:default");

        // 5. Автогенерація
        FoodComponent foodComponent = stack.get(DataComponentTypes.FOOD);
        if (foodComponent != null) {
            return generateFromVanilla(foodComponent, itemId);
        }

        return null; // Якщо це взагалі не їжа
    }

    public static int AUTO_GEN_DURATION_MULT = 1200;
    public static double AUTO_GEN_HEALTH_MULT = 0.5;

    private static FoodBuffData generateFromVanilla(FoodComponent food, Identifier itemId) {
        // Формула автогенерації тепер використовує змінні
        int durationTicks = food.nutrition() * AUTO_GEN_DURATION_MULT;
        double healthBonus = food.nutrition() * AUTO_GEN_HEALTH_MULT;

        return new FoodBuffData(
                "item:" + itemId.toString(),
                durationTicks,
                food.nutrition(),
                food.saturation(), // Використовуємо .saturation() для 1.21.1
                healthBonus,
                new ArrayList<>(),
                new ArrayList<>()
        );
    }

    public static int getConfigCount() {
        return CONFIGS.size();
    }
}