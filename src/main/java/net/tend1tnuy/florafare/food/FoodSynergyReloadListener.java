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

import net.tend1tnuy.florafare.food.DatapackDiagnostics.Category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code data/<namespace>/food_synergies/}.
 *
 * <p>A file is an object with an {@code entries} list, a single synergy object, or a bare
 * list of synergies. Files are read in the same deterministic order as
 * {@link FoodReloadListener} — data pack order, then file id — so when two files define
 * one synergy id the later one wins on every server, and the replacement is reported.
 */
public class FoodSynergyReloadListener extends JsonDataLoader implements IdentifiableResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static final String DATA_TYPE = "food_synergies";

    static final Set<String> FILE_KEYS    = Set.of("entries");
    static final Set<String> SYNERGY_KEYS = Set.of("id", "requirements", "duration", "health_bonus",
            "effects", "attributes", AftermathParser.KEY_AFTERMATH);

    private final DatapackLookup lookup;
    private DatapackDiagnostics.Collector diagnostics =
            DatapackDiagnostics.begin(DatapackDiagnostics.Phase.SYNERGIES);

    /** Which file defined each synergy id this reload, to name both sides of a duplicate. */
    private final Map<String, String> definedIn = new HashMap<>();

    public FoodSynergyReloadListener() {
        this(DatapackLookup.LIVE);
    }

    /** For tests, which cannot touch the real registries. */
    FoodSynergyReloadListener(DatapackLookup lookup) {
        super(GSON, DATA_TYPE);
        this.lookup = lookup;
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of(Florafare.MOD_ID, "food_synergies_loader");
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared, ResourceManager manager, Profiler profiler) {
        // Same reason as in FoodReloadListener: the cached client payload is built from
        // this map and has to be dropped whenever the map is rebuilt, world load included.
        Florafare.invalidateSyncPayloads();

        DatapackDiagnostics.Collector report = load(prepared,
                FoodReloadListener.packOrder(manager, DATA_TYPE, prepared.keySet()));

        Florafare.LOGGER.info("Loaded {} secret food synergies!", FoodSynergyManager.getAllSynergies().size());
        report.publish();
    }

    /** The load minus the server-only parts; see {@link FoodReloadListener#load}. Not yet published. */
    DatapackDiagnostics.Collector load(Map<Identifier, JsonElement> prepared,
                                       Comparator<Identifier> order) {
        FoodSynergyManager.clear();
        definedIn.clear();
        diagnostics = DatapackDiagnostics.begin(DatapackDiagnostics.Phase.SYNERGIES);

        List<Identifier> files = new ArrayList<>(prepared.keySet());
        files.sort(order);
        for (Identifier id : files) {
            JsonElement jsonElement = prepared.get(id);
            String file = id.toString();
            try {
                if (jsonElement.isJsonArray()) {
                    // A bare list of synergies. Used to be skipped without a word, which
                    // read as "the file loaded and holds nothing"; it is the same shape
                    // food_buffs has always accepted, so it is accepted here too.
                    for (JsonElement element : jsonElement.getAsJsonArray()) parseEntry(element, id);
                } else if (jsonElement.isJsonObject()) {
                    JsonObject obj = jsonElement.getAsJsonObject();
                    if (obj.has("entries")) {
                        if (!obj.get("entries").isJsonArray()) {
                            diagnostics.error(Category.MALFORMED_FILE, file, null,
                                    "'entries' must be a list ([ {...}, {...} ]) of synergy objects, but is "
                                            + JsonFields.render(obj.get("entries"))
                                            + "; nothing in this file was loaded");
                            continue;
                        }
                        List<String> unknown = JsonFields.unknownKeys(obj, FILE_KEYS);
                        if (!unknown.isEmpty()) {
                            diagnostics.warn(Category.UNKNOWN_KEY, file, null,
                                    "unknown key(s) at the file's root: "
                                            + FoodReloadListener.describeKeys(unknown, FILE_KEYS) + "; ignored");
                        }
                        for (JsonElement element : obj.getAsJsonArray("entries")) parseEntry(element, id);
                    } else {
                        parseEntry(obj, id);
                    }
                } else {
                    diagnostics.error(Category.MALFORMED_FILE, file, null,
                            "is neither a JSON object nor a list of synergies; nothing in it was loaded");
                }
            } catch (Exception e) {
                diagnostics.error(Category.MALFORMED_FILE, file, null,
                        "could not be read (" + e + "); nothing in it was loaded");
            }
        }
        return diagnostics;
    }

    /**
     * Parses one synergy, keeping its failure to itself.
     *
     * <p>Same reason as in {@code FoodReloadListener}: the only {@code catch} was around
     * the whole file, so one bad entry discarded every synergy after it and the log named
     * only the file.
     */
    private void parseEntry(JsonElement element, Identifier fileId) {
        String file = fileId.toString();
        if (element == null || !element.isJsonObject()) {
            diagnostics.error(Category.MALFORMED_ENTRY, file, null,
                    "a synergy entry is not a JSON object (" + JsonFields.render(element) + "); skipped");
            return;
        }
        JsonObject json = element.getAsJsonObject();
        try {
            parseAndRegister(json, fileId);
        } catch (Exception e) {
            diagnostics.error(Category.MALFORMED_ENTRY, file, JsonFields.getString(json, "id", null),
                    "could not be parsed (" + e + ") and was skipped; the rest of the file is unaffected");
        }
    }

    private void parseAndRegister(JsonObject json, Identifier fileId) {
        String file = fileId.toString();
        String id;
        try (JsonFields.Scope ignored = JsonFields.reportTo((key, message) -> { })) {
            id = JsonFields.getString(json, "id");
        }
        if (id == null) {
            List<String> unknown = JsonFields.unknownKeys(json, SYNERGY_KEYS);
            diagnostics.error(Category.MALFORMED_ENTRY, file, null,
                    "a synergy has a missing or non-string 'id'; skipped"
                            + (unknown.isEmpty() ? "" : " (unrecognized: "
                                    + FoodReloadListener.describeKeys(unknown, union()) + ")"));
            return;
        }

        List<String> unknownKeys = JsonFields.unknownKeys(json, SYNERGY_KEYS);
        if (!unknownKeys.isEmpty()) {
            diagnostics.warn(Category.UNKNOWN_KEY, file, id,
                    "unknown key(s): " + FoodReloadListener.describeKeys(unknownKeys, SYNERGY_KEYS) + "; ignored");
        }

        try (JsonFields.Scope ignored = JsonFields.reportTo(
                (key, message) -> diagnostics.warn(Category.WRONG_TYPE, file, id, message))) {
            parseFields(json, file, id);
        }
    }

    /** Synergy keys plus the file-level ones, for suggestions on an object that may be either. */
    private static Set<String> union() {
        Set<String> all = new HashSet<>(SYNERGY_KEYS);
        all.addAll(FILE_KEYS);
        return all;
    }

    private void parseFields(JsonObject json, String file, String id) {
        // A synergy with no requirements is satisfied by everything, so it would switch
        // on the moment it loaded and never switch off — and with no requirement to read
        // a remaining duration from, it took Integer.MAX_VALUE ticks and became
        // permanent. Rejected here rather than left for the component to guard against.
        if (!JsonFields.hasArray(json, "requirements")
                || json.getAsJsonArray("requirements").isEmpty()) {
            diagnostics.error(Category.MALFORMED_ENTRY, file, id,
                    "has no 'requirements' list; a synergy with no requirements would be "
                            + "permanently active, so it was skipped");
            return;
        }

        List<String> requirements = new ArrayList<>();
        JsonArray reqArray = json.getAsJsonArray("requirements");
        for (JsonElement re : reqArray) {
            if (!re.isJsonPrimitive()) {
                diagnostics.error(Category.INVALID_REQUIREMENT, file, id,
                        "has a requirement that is not a string (" + JsonFields.render(re)
                                + "); that requirement was skipped");
                continue;
            }
            // Normalize so requirements match the canonical food target keys
            // (bare item ids / "#" tag ids).
            String requirement = FoodBuffManager.normalizeTarget(re.getAsString());
            validateRequirement(requirement, file, id);
            requirements.add(requirement);
        }
        // Every requirement could have been thrown out above, which puts us back at the
        // "satisfied by everything, permanently active" case the check further up exists
        // to prevent.
        if (requirements.isEmpty()) {
            diagnostics.error(Category.MALFORMED_ENTRY, file, id,
                    "has no usable requirements left; skipped");
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
                if (!e.isJsonObject()) {
                    diagnostics.error(Category.INVALID_EFFECT, file, id,
                            "an item in 'effects' is not an object (" + JsonFields.render(e)
                                    + "); that effect was skipped");
                    continue;
                }
                JsonObject effObj = e.getAsJsonObject();
                reportNestedUnknownKeys(effObj, FoodReloadListener.EFFECT_KEYS, file, id, "an effect");
                String rawId = JsonFields.getString(effObj, "id");
                Identifier effId = rawId == null ? null : Identifier.tryParse(rawId);
                if (effId == null || !lookup.effectExists(effId)) {
                    diagnostics.error(Category.INVALID_EFFECT, file, id,
                            "lists effect '" + rawId + "', which does not exist; that effect was skipped");
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
                if (!e.isJsonObject()) {
                    diagnostics.error(Category.INVALID_ATTRIBUTE, file, id,
                            "an item in 'attributes' is not an object (" + JsonFields.render(e)
                                    + "); that attribute was skipped");
                    continue;
                }
                JsonObject attrObj = e.getAsJsonObject();
                reportNestedUnknownKeys(attrObj, FoodReloadListener.ATTRIBUTE_KEYS, file, id, "an attribute");
                String rawId = JsonFields.getString(attrObj, "attribute");
                Identifier attrId = rawId == null ? null : Identifier.tryParse(rawId);
                if (attrId == null || !lookup.attributeExists(attrId)) {
                    diagnostics.error(Category.INVALID_ATTRIBUTE, file, id,
                            "lists attribute '" + rawId + "', which does not exist; that attribute "
                                    + "was skipped");
                    continue;
                }
                // Same parser, and the same rule, as a food buff's attribute lines: legacy
                // names are understood, an unknown one costs this line and says so.
                String rawOperation = JsonFields.getString(attrObj, "operation", "add_value");
                String operation    = AttributeOperations.canonicalize(rawOperation);
                if (operation == null) {
                    diagnostics.error(Category.UNKNOWN_OPERATION, file, id,
                            "gives attribute '" + rawId + "' the unknown operation '" + rawOperation
                                    + "' (expected one of "
                                    + String.join(", ", AttributeOperations.CANONICAL_NAMES)
                                    + "); that attribute was skipped");
                    continue;
                }
                attributes.add(new FoodBuffData.AttributeData(
                        attrId,
                        JsonFields.getDouble(attrObj, "amount", 0.0),
                        operation
                ));
            }
        }

        // Same parser and the same rules as a food buff's; see AftermathParser.
        FoodAftermath aftermath = AftermathParser.parse(json, lookup, diagnostics, file, id);

        FoodSynergyData synergy = new FoodSynergyData(id, requirements, duration, healthBonus,
                effects, attributes, aftermath);
        FoodSynergyData replaced = FoodSynergyManager.putSynergy(synergy, file);
        String previousFile = definedIn.put(id, file);
        if (replaced != null) {
            // Used to be a silent overwrite, with the survivor decided by hash order.
            String previous = previousFile != null ? previousFile : FoodBuffManager.API_SOURCE;
            diagnostics.warn(Category.DUPLICATE_SYNERGY, file, id,
                    previous.equals(file)
                            ? "is defined more than once in this file; the last definition is the one in force"
                            : "is also defined by " + previous + "; this definition replaces it because it "
                                    + "loads later (data pack order, then file id). Rename one of them "
                                    + "if both are meant to exist");
        }
    }

    /** Warns about an item requirement that names no existing item (tags are checked later). */
    private void validateRequirement(String requirement, String file, String id) {
        if (requirement.startsWith("#")) {
            if (Identifier.tryParse(requirement.substring(1)) == null) {
                diagnostics.warn(Category.INVALID_REQUIREMENT, file, id,
                        "requirement '" + requirement + "' is not a valid tag id; this synergy "
                                + "can never activate");
            }
            return;
        }
        Identifier itemId = Identifier.tryParse(requirement);
        if (itemId == null) {
            diagnostics.warn(Category.INVALID_REQUIREMENT, file, id,
                    "requirement '" + requirement + "' is not a valid item id; this synergy can "
                            + "never activate");
        } else if (!lookup.itemExists(itemId) && lookup.modLoaded(itemId.getNamespace())) {
            diagnostics.warn(Category.INVALID_REQUIREMENT, file, id,
                    "requirement '" + requirement + "' names an item that does not exist; this "
                            + "synergy can never activate");
        }
    }

    private void reportNestedUnknownKeys(JsonObject json, Set<String> known, String file, String id,
                                         String where) {
        List<String> unknown = JsonFields.unknownKeys(json, known);
        if (unknown.isEmpty()) return;
        diagnostics.warn(Category.UNKNOWN_KEY, file, id,
                "unknown key(s) in " + where + ": " + FoodReloadListener.describeKeys(unknown, known)
                        + "; ignored");
    }
}