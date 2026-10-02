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
import org.jetbrains.annotations.Nullable;

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
            "effects", "attributes", AftermathParser.KEY_AFTERMATH, "timer", "overdrive",
            "lockout", "lockout_group");

    /** Every key an {@code overdrive} object reads. */
    static final Set<String> OVERDRIVE_KEYS = Set.of("stages");

    /** The lockout keys, which belong at the synergy's top level and not in its overdrive. */
    private static final Set<String> LOCKOUT_KEYS = Set.of("lockout", "lockout_group");

    /** Every key one overdrive stage reads: the synergy's own effect fields, nothing else. */
    static final Set<String> STAGE_KEYS = Set.of("health_bonus", "effects", "attributes",
            AftermathParser.KEY_AFTERMATH);

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
        List<FoodBuffData.EffectData> effects = parseEffects(json, duration, file, id, null);
        List<FoodBuffData.AttributeData> attributes = parseAttributes(json, file, id, null);

        // Same parser and the same rules as a food buff's; see AftermathParser.
        FoodAftermath aftermath = AftermathParser.parse(json, lookup, diagnostics, file, id);

        SynergyOverdrive overdrive = parseOverdrive(json, duration, file, id);
        FoodSynergyData.Timer timer = parseTimer(json, duration, overdrive != null, file, id);
        String group = parseLockoutGroup(json, file, id);
        boolean lockout = parseLockout(json, group != null, file, id);
        if (!lockout) group = null;

        FoodSynergyData synergy = new FoodSynergyData(id, requirements, duration, healthBonus,
                effects, attributes, aftermath, timer, overdrive, lockout, group);
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

    /**
     * The synergy's {@code timer}: {@code "members"} (the default) or {@code "own"}.
     *
     * <p>Every way of getting it wrong falls back to the members' timer, which is what the
     * synergy would have done before the field existed, and is reported as an error:
     * <ul>
     *   <li>a value that is neither name;</li>
     *   <li>{@code "own"} with no positive {@code duration} — an own timer <em>is</em> its
     *       duration, and one of zero would end the synergy on the tick it started;</li>
     *   <li>{@code "own"} on a synergy with an {@code overdrive}: a stage moves up when a bite
     *       lengthens the shortest ingredient's remaining time, which only means anything
     *       while the synergy follows that time. The overdrive is kept and the timer
     *       dropped, rather than the other way round, because the overdrive carries the
     *       stages and their aftermaths — much more of the author's design to lose.</li>
     * </ul>
     */
    private FoodSynergyData.Timer parseTimer(JsonObject json, int duration, boolean hasOverdrive,
                                             String file, String id) {
        JsonElement element = json.get("timer");
        if (element == null || element.isJsonNull()) return FoodSynergyData.Timer.MEMBERS;

        FoodSynergyData.Timer timer = element.isJsonPrimitive()
                ? FoodSynergyData.Timer.parse(element.getAsString()) : null;
        if (timer == null) {
            diagnostics.error(Category.INVALID_TIMER, file, id,
                    "'timer' must be \"members\" or \"own\", but is " + JsonFields.render(element)
                            + "; the members' timer is used");
            return FoodSynergyData.Timer.MEMBERS;
        }
        if (timer != FoodSynergyData.Timer.OWN) return timer;

        if (duration <= 0) {
            diagnostics.error(Category.INVALID_TIMER, file, id,
                    "has 'timer': \"own\" but no positive 'duration' for it to run for (" + duration
                            + "); the members' timer is used");
            return FoodSynergyData.Timer.MEMBERS;
        }
        if (hasOverdrive) {
            diagnostics.error(Category.INVALID_TIMER, file, id,
                    "has both 'timer': \"own\" and an 'overdrive'; overdrive stages advance when a "
                            + "bite lengthens the shortest ingredient, so they need the members' timer. "
                            + "The overdrive was kept and the members' timer is used");
            return FoodSynergyData.Timer.MEMBERS;
        }
        return timer;
    }

    /**
     * The synergy's {@code lockout_group}: a valid identifier, or null when it has none or it
     * is not one (an error — the synergy then locks out at most itself).
     */
    @Nullable
    private String parseLockoutGroup(JsonObject json, String file, String id) {
        JsonElement element = json.get("lockout_group");
        if (element == null || element.isJsonNull()) return null;
        String raw = element.isJsonPrimitive() ? element.getAsString() : null;
        Identifier group = raw == null ? null : Identifier.tryParse(raw);
        if (group == null) {
            diagnostics.error(Category.INVALID_LOCKOUT, file, id,
                    "'lockout_group' " + JsonFields.render(element) + " is not a valid identifier "
                            + "(namespace:path, lowercase); "
                            + "it was ignored, so the synergy locks out at most itself");
            return null;
        }
        return group.toString();
    }

    /**
     * The synergy's {@code lockout}. A group is a lockout by definition, so naming a valid
     * one turns it on unless the file says otherwise; saying otherwise makes the group mean
     * nothing, which is worth a word.
     */
    private boolean parseLockout(JsonObject json, boolean hasGroup, String file, String id) {
        boolean lockout = JsonFields.getBoolean(json, "lockout", hasGroup);
        if (!lockout && hasGroup) {
            diagnostics.warn(Category.INVALID_LOCKOUT, file, id,
                    "names a 'lockout_group' but sets 'lockout' to false; the group has no effect");
        }
        return lockout;
    }

    /**
     * The synergy's {@code overdrive} block, or null when it has none (or it is unusable).
     *
     * <p>Each stage is read with the very parsers the synergy's own fields go through, so a
     * stage's effect, attribute and aftermath lines are held to the same rules and reported
     * the same way, with the stage named. A stage that is not an object is dropped — and
     * the report says the later stages moved up one, since that changes which numeral the
     * player sees for them.
     */
    @Nullable
    private SynergyOverdrive parseOverdrive(JsonObject json, int duration, String file, String id) {
        JsonElement element = json.get("overdrive");
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonObject()) {
            diagnostics.error(Category.INVALID_OVERDRIVE, file, id,
                    "'overdrive' must be an object ({ \"stages\": [...], ... }), but is "
                            + JsonFields.render(element) + "; it was ignored");
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        // The lockout keys get a message of their own: they are real keys in the wrong
        // place, which a "did you mean" over the overdrive's own keys would not explain.
        List<String> misplaced = new ArrayList<>();
        for (String key : LOCKOUT_KEYS) if (obj.has(key)) misplaced.add(key);
        if (!misplaced.isEmpty()) {
            misplaced.sort(null);
            diagnostics.warn(Category.INVALID_OVERDRIVE, file, id,
                    "its overdrive has " + String.join(" and ", misplaced) + ", which belong at the "
                            + "synergy's top level (beside 'overdrive', not inside it); ignored here");
        }
        List<String> unknown = new ArrayList<>(JsonFields.unknownKeys(obj, OVERDRIVE_KEYS));
        unknown.removeAll(LOCKOUT_KEYS);
        if (!unknown.isEmpty()) {
            diagnostics.warn(Category.UNKNOWN_KEY, file, id,
                    "unknown key(s) in its overdrive: "
                            + FoodReloadListener.describeKeys(unknown, OVERDRIVE_KEYS) + "; ignored");
        }

        List<SynergyOverdrive.Stage> stages = new ArrayList<>();
        JsonElement rawStages = obj.get("stages");
        if (rawStages != null && !rawStages.isJsonNull() && !rawStages.isJsonArray()) {
            diagnostics.error(Category.INVALID_OVERDRIVE, file, id,
                    "its overdrive's 'stages' must be a list of stage objects, but is "
                            + JsonFields.render(rawStages) + "; it has no stages past I");
        } else if (rawStages != null && rawStages.isJsonArray()) {
            int written = 1;
            for (JsonElement raw : rawStages.getAsJsonArray()) {
                written++;
                String label = "overdrive stage " + net.tend1tnuy.florafare.util.RomanNumerals.of(written);
                if (!raw.isJsonObject()) {
                    diagnostics.error(Category.INVALID_OVERDRIVE, file, id,
                            label + " is not an object (" + JsonFields.render(raw) + "); it was "
                                    + "skipped, and the stages after it each move up one");
                    continue;
                }
                stages.add(parseStage(raw.getAsJsonObject(), duration, file, id, label));
            }
        }

        if (stages.isEmpty()) {
            diagnostics.warn(Category.INVALID_OVERDRIVE, file, id,
                    "has an 'overdrive' with no stages past I; stage I is already the last, so the "
                            + "first bite that prolongs it overloads it");
        }
        return new SynergyOverdrive(stages);
    }

    /** One overdrive stage, through the same parsers as the synergy's own fields. */
    private SynergyOverdrive.Stage parseStage(JsonObject stage, int duration, String file, String id,
                                              String label) {
        reportNestedUnknownKeys(stage, STAGE_KEYS, file, id, label);
        double healthBonus = JsonFields.getDouble(stage, "health_bonus", 0.0);
        List<FoodBuffData.EffectData> effects = parseEffects(stage, duration, file, id, label);
        List<FoodBuffData.AttributeData> attributes = parseAttributes(stage, file, id, label);
        // The aftermath parser names the entry, not the part of it; the stage goes into
        // the target it reports under so "which aftermath" is never a question.
        FoodAftermath aftermath = AftermathParser.parse(stage, lookup, diagnostics, file,
                id + " (" + label + ")");
        return new SynergyOverdrive.Stage(healthBonus, effects, attributes, aftermath);
    }

    /**
     * A synergy's (or one overdrive stage's) {@code effects} list, every line validated the
     * way a food buff's is.
     *
     * @param stage the part of the synergy the list is in, e.g. {@code "overdrive stage II"},
     *              or null for its own top level — so the report says which list a bad
     *              line was in
     */
    private List<FoodBuffData.EffectData> parseEffects(JsonObject json, int duration, String file,
                                                       String id, @Nullable String stage) {
        String where = stage == null ? "" : stage + ": ";
        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        if (!JsonFields.hasArray(json, "effects")) return effects;
        for (JsonElement e : json.getAsJsonArray("effects")) {
            if (!e.isJsonObject()) {
                diagnostics.error(Category.INVALID_EFFECT, file, id,
                        where + "an item in 'effects' is not an object (" + JsonFields.render(e)
                                + "); that effect was skipped");
                continue;
            }
            JsonObject effObj = e.getAsJsonObject();
            reportNestedUnknownKeys(effObj, FoodReloadListener.EFFECT_KEYS, file, id,
                    stage == null ? "an effect" : "an effect in " + stage);
            String rawId = JsonFields.getString(effObj, "id");
            Identifier effId = rawId == null ? null : Identifier.tryParse(rawId);
            if (effId == null || !lookup.effectExists(effId)) {
                diagnostics.error(Category.INVALID_EFFECT, file, id,
                        where + "lists effect '" + rawId + "', which does not exist; that effect was skipped");
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
        return effects;
    }

    /** The same for {@code attributes}; see {@link #parseEffects} for {@code stage}. */
    private List<FoodBuffData.AttributeData> parseAttributes(JsonObject json, String file, String id,
                                                             @Nullable String stage) {
        String where = stage == null ? "" : stage + ": ";
        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        if (!JsonFields.hasArray(json, "attributes")) return attributes;
        for (JsonElement e : json.getAsJsonArray("attributes")) {
            if (!e.isJsonObject()) {
                diagnostics.error(Category.INVALID_ATTRIBUTE, file, id,
                        where + "an item in 'attributes' is not an object (" + JsonFields.render(e)
                                + "); that attribute was skipped");
                continue;
            }
            JsonObject attrObj = e.getAsJsonObject();
            reportNestedUnknownKeys(attrObj, FoodReloadListener.ATTRIBUTE_KEYS, file, id,
                    stage == null ? "an attribute" : "an attribute in " + stage);
            String rawId = JsonFields.getString(attrObj, "attribute");
            Identifier attrId = rawId == null ? null : Identifier.tryParse(rawId);
            if (attrId == null || !lookup.attributeExists(attrId)) {
                diagnostics.error(Category.INVALID_ATTRIBUTE, file, id,
                        where + "lists attribute '" + rawId + "', which does not exist; that attribute "
                                + "was skipped");
                continue;
            }
            // Same parser, and the same rule, as a food buff's attribute lines: legacy
            // names are understood, an unknown one costs this line and says so.
            String rawOperation = JsonFields.getString(attrObj, "operation", "add_value");
            String operation    = AttributeOperations.canonicalize(rawOperation);
            if (operation == null) {
                diagnostics.error(Category.UNKNOWN_OPERATION, file, id,
                        where + "gives attribute '" + rawId + "' the unknown operation '" + rawOperation
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
        return attributes;
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