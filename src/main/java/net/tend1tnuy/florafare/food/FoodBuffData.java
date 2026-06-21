package net.tend1tnuy.florafare.food;

import net.minecraft.util.Identifier;
import java.util.List;

public record FoodBuffData(
        String target,
        int duration,
        int nutrition,
        float saturation,
        double healthBonus,
        List<EffectData> effects,
        List<AttributeData> attributes
) {
    public record EffectData(Identifier id, int duration, int amplifier) {}

    // operation: "add_value", "add_multiplied_base", "add_multiplied_total" (для 1.21.1)
    public record AttributeData(Identifier attributeId, double amount, String operation) {}
}