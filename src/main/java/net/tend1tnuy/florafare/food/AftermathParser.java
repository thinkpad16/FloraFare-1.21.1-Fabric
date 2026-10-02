package net.tend1tnuy.florafare.food;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads the optional {@code aftermath} object of a {@code food_buffs} or
 * {@code food_synergies} entry into a {@link FoodAftermath}.
 *
 * <p>One parser for both listeners, so a buff's aftermath and a synergy's cannot drift
 * apart the way their effect and attribute lists once did. Every line inside it is held
 * to the same rules as the entry's own: an effect or attribute that does not exist costs
 * that line, an unknown operation costs that line (through {@link AttributeOperations},
 * which knows the legacy names), and each is reported with the file and the entry it came
 * from. Problems with the object itself go under {@link Category#INVALID_AFTERMATH}.
 *
 * <p>Called from inside the listener's {@link JsonFields#reportTo} scope, so a field of
 * the wrong JSON type ({@code "duration": "five minutes"}) is reported as a
 * {@code WRONG_TYPE} warning against the entry, exactly as it would be one level up.
 */
final class AftermathParser {

    private AftermathParser() {}

    /** The key an entry carries it under. */
    static final String KEY_AFTERMATH = "aftermath";

    private static final String KEY_DURATION     = "duration";
    private static final String KEY_HEALTH_BONUS = "health_bonus";
    private static final String KEY_EFFECTS      = "effects";
    private static final String KEY_ATTRIBUTES   = "attributes";

    /** Every key an aftermath object reads. */
    static final Set<String> AFTERMATH_KEYS =
            Set.of(KEY_DURATION, KEY_HEALTH_BONUS, KEY_EFFECTS, KEY_ATTRIBUTES);

    /**
     * Parses {@code entry}'s aftermath.
     *
     * <p>The rules, in the order they bite:
     * <ul>
     *   <li>absent or {@code null} — {@link FoodAftermath#NONE}, silently;</li>
     *   <li>not an object — an error, and no aftermath;</li>
     *   <li>an effect with no {@code duration} of its own takes the aftermath's; with no
     *       aftermath duration either it has nothing to go on, and is skipped;</li>
     *   <li>no {@code duration} given — the longest finite effect duration, so a
     *       nausea-only hangover is shown on the HUD for exactly as long as the nausea;</li>
     *   <li>nothing in it — a warning, and no aftermath: a row on the HUD that does
     *       nothing is worse than none;</li>
     *   <li>a health bonus or attributes with no positive duration to hold them for — an
     *       error; the modifiers are dropped and any effects are kept, since those carry
     *       durations of their own.</li>
     * </ul>
     *
     * @param target the entry's target or synergy id, for the report
     */
    static FoodAftermath parse(JsonObject entry, DatapackLookup lookup,
                               DatapackDiagnostics.Collector diagnostics,
                               String file, String target) {
        JsonElement element = entry.get(KEY_AFTERMATH);
        if (element == null || element.isJsonNull()) return FoodAftermath.NONE;
        if (!element.isJsonObject()) {
            diagnostics.error(Category.INVALID_AFTERMATH, file, target,
                    "'aftermath' must be an object ({ \"duration\": ..., ... }), but is "
                            + JsonFields.render(element) + "; it was ignored");
            return FoodAftermath.NONE;
        }
        JsonObject json = element.getAsJsonObject();

        List<String> unknown = JsonFields.unknownKeys(json, AFTERMATH_KEYS);
        if (!unknown.isEmpty()) {
            diagnostics.warn(Category.UNKNOWN_KEY, file, target,
                    "unknown key(s) in its aftermath: "
                            + FoodReloadListener.describeKeys(unknown, AFTERMATH_KEYS) + "; ignored");
        }

        boolean hasDuration = json.has(KEY_DURATION) && !json.get(KEY_DURATION).isJsonNull();
        int duration = JsonFields.getInt(json, KEY_DURATION, 0);
        double healthBonus = JsonFields.getDouble(json, KEY_HEALTH_BONUS, 0.0);

        List<FoodBuffData.EffectData> effects =
                parseEffects(json, hasDuration ? duration : 0, lookup, diagnostics, file, target);
        List<FoodBuffData.AttributeData> attributes =
                parseAttributes(json, lookup, diagnostics, file, target);

        if (!hasDuration) duration = longestFiniteDuration(effects);

        FoodAftermath aftermath = new FoodAftermath(duration, healthBonus, effects, attributes);
        if (aftermath.isEmpty()) {
            diagnostics.warn(Category.INVALID_AFTERMATH, file, target,
                    "has an 'aftermath' with nothing in it to apply (no health_bonus, attributes "
                            + "or effects that loaded); it was ignored");
            return FoodAftermath.NONE;
        }
        if (aftermath.hasModifiers() && duration <= 0) {
            boolean keepEffects = !effects.isEmpty();
            diagnostics.error(Category.INVALID_AFTERMATH, file, target,
                    "has an 'aftermath' with a health_bonus or attributes but no positive "
                            + "'duration' to hold them for; "
                            + (keepEffects ? "only its effects were kept" : "it was ignored"));
            return keepEffects
                    ? new FoodAftermath(0, 0.0, effects, List.of())
                    : FoodAftermath.NONE;
        }
        return aftermath;
    }

    /**
     * The duration an aftermath without one of its own is given: its longest effect,
     * ignoring infinite ones ({@code -1}), which have no length to borrow. Zero when there
     * is nothing to go on — an effects-only aftermath that tracks nothing.
     */
    static int longestFiniteDuration(List<FoodBuffData.EffectData> effects) {
        int longest = 0;
        for (FoodBuffData.EffectData effect : effects) {
            if (effect.duration() > longest) longest = effect.duration();
        }
        return longest;
    }

    private static List<FoodBuffData.EffectData> parseEffects(JsonObject json, int defaultDuration,
                                                             DatapackLookup lookup,
                                                             DatapackDiagnostics.Collector diagnostics,
                                                             String file, String target) {
        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (!JsonFields.hasArray(json, KEY_EFFECTS)) return effects;
        for (JsonElement e : json.getAsJsonArray(KEY_EFFECTS)) {
            if (!e.isJsonObject()) {
                diagnostics.error(Category.INVALID_EFFECT, file, target,
                        "an item in its aftermath's 'effects' is not an object ("
                                + JsonFields.render(e) + "); that effect was skipped");
                continue;
            }
            JsonObject effObj = e.getAsJsonObject();
            reportNestedUnknownKeys(effObj, FoodReloadListener.EFFECT_KEYS, diagnostics, file, target,
                    "an aftermath effect");
            String rawId = JsonFields.getString(effObj, "id");
            Identifier effId = rawId == null ? null : Identifier.tryParse(rawId);
            if (effId == null || !lookup.effectExists(effId)) {
                diagnostics.error(Category.INVALID_EFFECT, file, target,
                        "its aftermath lists effect '" + rawId + "', which does not exist; "
                                + "that effect was skipped");
                continue;
            }
            boolean ownDuration = effObj.has(KEY_DURATION) && !effObj.get(KEY_DURATION).isJsonNull();
            if (!ownDuration && defaultDuration <= 0) {
                // An entry's own effects borrow the buff's length when they leave theirs
                // out. An aftermath with no duration has none to lend, and guessing one
                // would turn a typo into a minute of Nausea nobody asked for.
                diagnostics.error(Category.INVALID_EFFECT, file, target,
                        "its aftermath effect '" + rawId + "' has no 'duration', and neither "
                                + "does the aftermath; that effect was skipped");
                continue;
            }
            effects.add(new FoodBuffData.EffectData(
                    effId,
                    JsonFields.getInt(effObj, KEY_DURATION, defaultDuration),
                    JsonFields.getInt(effObj, "amplifier", 0)));
        }
        return effects;
    }

    private static List<FoodBuffData.AttributeData> parseAttributes(JsonObject json,
                                                                   DatapackLookup lookup,
                                                                   DatapackDiagnostics.Collector diagnostics,
                                                                   String file, String target) {
        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (!JsonFields.hasArray(json, KEY_ATTRIBUTES)) return attributes;
        for (JsonElement e : json.getAsJsonArray(KEY_ATTRIBUTES)) {
            if (!e.isJsonObject()) {
                diagnostics.error(Category.INVALID_ATTRIBUTE, file, target,
                        "an item in its aftermath's 'attributes' is not an object ("
                                + JsonFields.render(e) + "); that attribute was skipped");
                continue;
            }
            JsonObject attrObj = e.getAsJsonObject();
            reportNestedUnknownKeys(attrObj, FoodReloadListener.ATTRIBUTE_KEYS, diagnostics, file, target,
                    "an aftermath attribute");
            String rawId = JsonFields.getString(attrObj, "attribute");
            Identifier attrId = rawId == null ? null : Identifier.tryParse(rawId);
            if (attrId == null || !lookup.attributeExists(attrId)) {
                diagnostics.error(Category.INVALID_ATTRIBUTE, file, target,
                        "its aftermath lists attribute '" + rawId + "', which does not exist; "
                                + "that attribute was skipped");
                continue;
            }
            // The shared parser, so "multiply_total" means the same thing here as on the
            // entry itself — and an unknown name costs the line instead of becoming a
            // flat addition.
            String rawOperation = JsonFields.getString(attrObj, "operation", "add_value");
            String operation = AttributeOperations.canonicalize(rawOperation);
            if (operation == null) {
                diagnostics.error(Category.UNKNOWN_OPERATION, file, target,
                        "its aftermath gives attribute '" + rawId + "' the unknown operation '"
                                + rawOperation + "' (expected one of "
                                + String.join(", ", AttributeOperations.CANONICAL_NAMES)
                                + "); that attribute was skipped");
                continue;
            }
            attributes.add(new FoodBuffData.AttributeData(
                    attrId, JsonFields.getDouble(attrObj, "amount", 0.0), operation));
        }
        return attributes;
    }

    private static void reportNestedUnknownKeys(JsonObject json, Set<String> known,
                                                DatapackDiagnostics.Collector diagnostics,
                                                String file, String target, String where) {
        List<String> unknown = JsonFields.unknownKeys(json, known);
        if (unknown.isEmpty()) return;
        diagnostics.warn(Category.UNKNOWN_KEY, file, target,
                "unknown key(s) in " + where + ": "
                        + FoodReloadListener.describeKeys(unknown, known) + "; ignored");
    }
}
