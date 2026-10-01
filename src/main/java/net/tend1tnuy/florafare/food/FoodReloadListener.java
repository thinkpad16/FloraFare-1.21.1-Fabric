package net.tend1tnuy.florafare.food;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resource.JsonDataLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles the loading and parsing of food buff configurations from datapack JSON files.
 *
 * <p>Three file shapes are accepted, all in {@code data/<namespace>/food_buffs/}:
 * <ul>
 *   <li>an object with an {@code entries} list, plus optional file-wide {@code priority}
 *       and {@code always_edible} defaults;</li>
 *   <li>a single entry object at the root, whose {@code id} — when it has none — is taken
 *       from the file name ({@code mypack:meats/beef.json} targets {@code mypack:beef});</li>
 *   <li>(legacy) a bare list of entries, at priority 0.</li>
 * </ul>
 * Anything else is reported and skipped as a whole, rather than loading as nothing.
 *
 * <p>Files are applied in a fixed order — data pack order first (a pack enabled later,
 * i.e. higher in {@code /datapack list}, comes later), then by file id — so that when two
 * files define one target at the same priority the same one wins on every server and
 * every reload. The later one wins the tie, and the target is reported as ambiguous.
 * Within a file the entries apply top to bottom, so of two equal definitions in one file
 * the last wins.
 *
 * <p>Every problem found is reported to {@link DatapackDiagnostics} as well as the log.
 */
public class FoodReloadListener extends JsonDataLoader implements IdentifiableResourceReloadListener {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static final String DATA_TYPE = "food_buffs";

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
    private static final String KEY_DURATION_MULT = "duration_multiplier";
    private static final String KEY_HEALTH_MULT   = "health_multiplier";

    /** Keys an {@code entries} file reads at its root. */
    static final Set<String> FILE_KEYS = Set.of(KEY_ENTRIES, KEY_PRIORITY, KEY_ALWAYS_EDIBLE);

    /** Keys only an entry has — the ones that make a root object a single-entry file. */
    static final Set<String> ENTRY_ONLY_KEYS = Set.of(KEY_ID, KEY_DURATION, KEY_NUTRITION,
            KEY_SATURATION, KEY_HEALTH_BONUS, KEY_EFFECTS, KEY_ATTRIBUTES);

    /** Every key an entry reads. */
    static final Set<String> ENTRY_KEYS = union(ENTRY_ONLY_KEYS, Set.of(KEY_PRIORITY, KEY_ALWAYS_EDIBLE));

    static final Set<String> EFFECT_KEYS     = Set.of(KEY_ID, KEY_DURATION, KEY_AMPLIFIER);
    static final Set<String> ATTRIBUTE_KEYS  = Set.of(KEY_ATTRIBUTE, KEY_AMOUNT, KEY_OPERATION);
    static final Set<String> CONFIG_GEN_KEYS = Set.of(KEY_DURATION_MULT, KEY_HEALTH_MULT);

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    /** What the registries are asked while validating; {@link DatapackLookup#LIVE} outside tests. */
    private final DatapackLookup lookup;

    /** This reload's report; replaced at the start of every load. */
    private DatapackDiagnostics.Collector diagnostics = DatapackDiagnostics.begin(DatapackDiagnostics.Phase.FOOD_BUFFS);

    /** Entries whose target belongs to a mod that is not installed (counted per reload). */
    private int absentModTargets;

    public FoodReloadListener() {
        this(DatapackLookup.LIVE);
    }

    /** For tests, which cannot touch the real registries. */
    FoodReloadListener(DatapackLookup lookup) {
        super(GSON, DATA_TYPE);
        this.lookup = lookup;
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of(Florafare.MOD_ID, "food_buffs_loader");
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared,
                         ResourceManager manager, Profiler profiler) {
        // The serialized client payload is built from this map, so it is stale the
        // moment the map is rebuilt. Done here rather than only in END_DATA_PACK_RELOAD
        // because that event covers /reload alone — see Florafare#invalidateSyncPayloads.
        Florafare.invalidateSyncPayloads();

        DatapackDiagnostics.Collector report =
                load(prepared, packOrder(manager, DATA_TYPE, prepared.keySet()));

        // Last, over everything the pack just defined: an operator's live edits outrank
        // the files on disk, and this is the one place the config map is rebuilt — so
        // without it a /reload would quietly revert every /florafare edit. It also
        // re-reads what each override displaces, which is what makes a later reset put
        // back the entry *this* reload defined rather than the previous one's.
        FoodBuffOverrides.reapply();

        Florafare.LOGGER.info("Loaded {} food configurations!", FoodBuffManager.getConfigCount());
        if (absentModTargets > 0) {
            Florafare.LOGGER.info(
                    "{} entries target items from mods that are not installed; "
                            + "they stay inactive until those mods are present.",
                    absentModTargets);
        }
        report.publish();
    }

    /**
     * The whole load, minus the parts that need a running server: clears the config map,
     * applies {@code config_generation.json}, parses every file in {@code order}, and
     * reports priority conflicts. Package-private so a test can drive it with a stub
     * {@link DatapackLookup}. The returned report is not yet published.
     */
    DatapackDiagnostics.Collector load(Map<Identifier, JsonElement> prepared,
                                       Comparator<Identifier> order) {
        FoodBuffManager.clear();
        absentModTargets = 0;
        diagnostics = DatapackDiagnostics.begin(DatapackDiagnostics.Phase.FOOD_BUFFS);

        List<Identifier> files = new ArrayList<>(prepared.keySet());
        files.sort(order);

        applyConfigGeneration(prepared, files);

        for (Identifier fileId : files) {
            if (fileId.getPath().equals(CONFIG_GEN_KEY)) continue;
            try {
                parseFile(fileId, prepared.get(fileId));
            } catch (Exception e) {
                diagnostics.error(Category.MALFORMED_FILE, fileId.toString(), null,
                        "could not be read (" + e + "); nothing in it was loaded");
            }
        }

        reportPriorityConflicts();
        return diagnostics;
    }

    // -------------------------------------------------------------------------
    // ORDER
    //
    // JsonDataLoader hands the files over in a HashMap, so the order they were applied
    // in — and with it the winner of every same-priority tie — used to depend on the
    // hash codes of their ids. Two servers with identical packs could disagree, and
    // renaming a file could silently flip a winner. They are now sorted: by the data
    // pack each came from, in the order the packs are applied, then by file id.
    // -------------------------------------------------------------------------

    /**
     * Orders this reload's files by the data pack they come from — the order
     * {@code /datapack list} shows, a later pack sorting later — and then by file id.
     *
     * <p>A file id only ever comes from one pack (the topmost one shipping it; packs
     * below are overridden whole), so asking the resource manager which pack that is
     * costs one lookup per file. Should the lookup fail for any reason the order falls
     * back to file id alone, which is still deterministic.
     */
    public static Comparator<Identifier> packOrder(ResourceManager manager, String dataType,
                                                   java.util.Collection<Identifier> fileIds) {
        Comparator<Identifier> byId = Comparator.comparing(Identifier::toString);
        try {
            Map<String, Integer> packRank = new java.util.HashMap<>();
            int rank = 0;
            for (var it = manager.streamResourcePacks().iterator(); it.hasNext(); ) {
                packRank.putIfAbsent(it.next().getId(), rank++);
            }
            Map<Identifier, Integer> fileRank = new java.util.HashMap<>();
            for (Identifier fileId : fileIds) {
                Identifier resource = fileId.withPath(path -> dataType + "/" + path + ".json");
                int fileRankValue = manager.getResource(resource)
                        .map(found -> packRank.getOrDefault(found.getPackId(), -1))
                        .orElse(-1);
                fileRank.put(fileId, fileRankValue);
            }
            return Comparator.<Identifier>comparingInt(id -> fileRank.getOrDefault(id, -1))
                    .thenComparing(byId);
        } catch (Exception e) {
            Florafare.LOGGER.debug("Could not read the data pack order; ordering {} files by id.",
                    dataType, e);
            return byId;
        }
    }

    // -------------------------------------------------------------------------
    // CONFIG GENERATION
    // -------------------------------------------------------------------------

    /**
     * Seeds the auto-generation multipliers from {@code florafare.json}, then lets every
     * {@code config_generation.json} override them, in pack order.
     *
     * <p>Clamped to the same ranges the config file is. A datapack value used to be taken
     * as written, so {@code "health_multiplier": -1} gave every auto-generated food a
     * negative max-health modifier — eating lowered the player's health — and a huge
     * duration multiplier overflowed into buffs that expired on creation. Several files
     * are applied field by field, later packs last; that used to happen in hash order, so
     * which pack's value stuck was arbitrary.
     */
    private void applyConfigGeneration(Map<Identifier, JsonElement> prepared, List<Identifier> files) {
        // Seed from the global config default; a datapack's own config_generation.json
        // can still override it for this reload. Also fixes these statics staying stale
        // across /reload once no datapack defines the file anymore.
        int    durationMult = FlorafareConfig.autoGenDurationMultiplier;
        double healthMult   = FlorafareConfig.autoGenHealthMultiplier;

        List<Identifier> generationFiles = new ArrayList<>();
        for (Identifier fileId : files) {
            if (fileId.getPath().equals(CONFIG_GEN_KEY)) generationFiles.add(fileId);
        }
        if (generationFiles.size() > 1) {
            diagnostics.warn(Category.CONFIG_GENERATION, null, null,
                    generationFiles.size() + " data packs ship food_buffs/config_generation.json ("
                            + joinIds(generationFiles) + "). They apply in that order, field by "
                            + "field, so " + generationFiles.get(generationFiles.size() - 1)
                            + " has the final word on any field it sets");
        }

        for (Identifier fileId : generationFiles) {
            String file = fileId.toString();
            JsonElement element = prepared.get(fileId);
            if (element == null || !element.isJsonObject()) {
                diagnostics.error(Category.MALFORMED_FILE, file, null,
                        "config_generation.json must be a JSON object; ignored");
                continue;
            }
            JsonObject json = element.getAsJsonObject();
            reportUnknownKeys(json, CONFIG_GEN_KEYS, file, null, "config_generation.json");
            try (JsonFields.Scope ignored = JsonFields.reportTo(
                    (key, message) -> diagnostics.warn(Category.WRONG_TYPE, file, null, message))) {
                int rawDuration = JsonFields.getInt(json, KEY_DURATION_MULT, durationMult);
                durationMult = FlorafareConfig.clampAutoGenDuration(rawDuration);
                if (durationMult != rawDuration) {
                    diagnostics.warn(Category.CONFIG_GENERATION, file, null,
                            "'" + KEY_DURATION_MULT + "' is " + rawDuration + ", outside 0.."
                                    + FlorafareConfig.MAX_AUTO_GEN_DURATION_MULT + "; using "
                                    + durationMult);
                }
                double rawHealth = JsonFields.getDouble(json, KEY_HEALTH_MULT, healthMult);
                healthMult = FlorafareConfig.clampAutoGenHealth(rawHealth);
                if (Double.compare(healthMult, rawHealth) != 0) {
                    diagnostics.warn(Category.CONFIG_GENERATION, file, null,
                            "'" + KEY_HEALTH_MULT + "' is " + rawHealth + ", outside 0.."
                                    + FlorafareConfig.MAX_AUTO_GEN_HEALTH_MULT + "; using "
                                    + healthMult);
                }
            }
            Florafare.LOGGER.info("Loaded global auto-generation config from {}.", file);
        }

        FoodBuffManager.AUTO_GEN_DURATION_MULT = durationMult;
        FoodBuffManager.AUTO_GEN_HEALTH_MULT   = healthMult;
    }

    private static String joinIds(List<Identifier> ids) {
        List<String> names = new ArrayList<>(ids.size());
        for (Identifier id : ids) names.add(id.toString());
        return String.join(", ", names);
    }

    // -------------------------------------------------------------------------
    // FILE SHAPES
    // -------------------------------------------------------------------------

    /** How a {@code food_buffs} file is laid out. */
    enum FileShape {
        /** {@code {"entries": [...]}} — the standard form. */
        ENTRIES,
        /** One entry at the root; its {@code id} defaults to the file name. */
        SINGLE_ENTRY,
        /** A bare list of entries (legacy, priority 0). */
        LEGACY_ARRAY,
        /** Has {@code entries}, but not a list — e.g. a single entry object under it. */
        ENTRIES_NOT_A_LIST,
        /** An object with neither {@code entries} nor any entry field — usually a typo. */
        UNRECOGNIZED,
        /** Not an object or a list at all. */
        NOT_AN_OBJECT
    }

    /**
     * Classifies a file. A root object counts as a single entry only through a field that
     * belongs to entries alone — {@code priority} and {@code always_edible} are also
     * file-wide settings, so {@code {"priority": 5, "entires": [...]}} is a misspelled
     * entries file, not an entry for an item named after the file. For the same reason
     * a root key that looks like a misspelling of {@code entries} always wins.
     */
    static FileShape shapeOf(JsonElement json) {
        if (json == null) return FileShape.NOT_AN_OBJECT;
        if (json.isJsonArray()) return FileShape.LEGACY_ARRAY;
        if (!json.isJsonObject()) return FileShape.NOT_AN_OBJECT;

        JsonObject obj = json.getAsJsonObject();
        if (obj.has(KEY_ENTRIES)) {
            return obj.get(KEY_ENTRIES).isJsonArray() ? FileShape.ENTRIES : FileShape.ENTRIES_NOT_A_LIST;
        }
        for (String key : JsonFields.unknownKeys(obj, ENTRY_KEYS)) {
            if (KEY_ENTRIES.equals(JsonFields.suggestKey(key, FILE_KEYS))) return FileShape.UNRECOGNIZED;
        }
        for (String key : ENTRY_ONLY_KEYS) {
            if (obj.has(key)) return FileShape.SINGLE_ENTRY;
        }
        return FileShape.UNRECOGNIZED;
    }

    private void parseFile(Identifier fileId, JsonElement json) {
        String file = fileId.toString();
        KeyTally tally = new KeyTally();
        Set<String> targetsInFile = new HashSet<>();

        switch (shapeOf(json)) {
            case NOT_AN_OBJECT -> diagnostics.error(Category.MALFORMED_FILE, file, null,
                    "is neither a JSON object nor a list of entries; nothing in it was loaded");

            case LEGACY_ARRAY -> {
                int index = 0;
                for (JsonElement element : json.getAsJsonArray()) {
                    parseEntry(element, index++, null, fileId, 0, false, tally, targetsInFile);
                }
            }

            case ENTRIES_NOT_A_LIST -> diagnostics.error(Category.MALFORMED_FILE, file, null,
                    "'entries' must be a list ([ {...}, {...} ]) of entry objects, but is "
                            + JsonFields.render(json.getAsJsonObject().get(KEY_ENTRIES))
                            + "; nothing in this file was loaded");

            case UNRECOGNIZED -> {
                JsonObject obj = json.getAsJsonObject();
                StringBuilder message = new StringBuilder(
                        "has neither an 'entries' list nor any entry field (id, duration, "
                                + "nutrition, ...), so nothing in it was loaded");
                List<String> unknown = JsonFields.unknownKeys(obj, ENTRY_KEYS);
                if (!unknown.isEmpty()) {
                    message.append(". Unrecognized: ").append(describeKeys(unknown, union(FILE_KEYS, ENTRY_KEYS)));
                }
                diagnostics.error(Category.MALFORMED_FILE, file, null, message.toString());
            }

            case ENTRIES -> {
                JsonObject obj = json.getAsJsonObject();
                reportUnknownKeys(obj, FILE_KEYS, file, null, "the file's root");
                int filePriority;
                boolean fileAlwaysEdible;
                // Through JsonFields like every other read. These two are the file-level
                // defaults, so a bare getAsInt/getAsBoolean on a misspelled value
                // ("priority": "high") threw out to the catch and discarded the whole
                // file — which is the exact failure JsonFields exists to stop.
                try (JsonFields.Scope ignored = JsonFields.reportTo(
                        (key, message) -> diagnostics.warn(Category.WRONG_TYPE, file, null, message))) {
                    filePriority     = JsonFields.getInt(obj, KEY_PRIORITY, 0);
                    fileAlwaysEdible = JsonFields.getBoolean(obj, KEY_ALWAYS_EDIBLE, false);
                }
                int index = 0;
                for (JsonElement element : obj.getAsJsonArray(KEY_ENTRIES)) {
                    parseEntry(element, index++, null, fileId, filePriority, fileAlwaysEdible,
                            tally, targetsInFile);
                }
            }

            // The id-less form: a file holding one entry and nothing else, named after the
            // item it is for. It used to be reachable only with an "id" present, which made
            // the file-name fallback dead code and a file without one load as nothing.
            case SINGLE_ENTRY -> parseEntry(json, 0, defaultTargetFor(fileId), fileId, 0, false,
                    tally, targetsInFile);
        }

        tally.report(diagnostics, file);
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
    private void parseEntry(JsonElement element, int index, String defaultTargetFallback,
                            Identifier fileId, int filePriority, boolean fileAlwaysEdible,
                            KeyTally tally, Set<String> targetsInFile) {
        String file = fileId.toString();
        if (element == null || !element.isJsonObject()) {
            diagnostics.error(Category.MALFORMED_ENTRY, file, null,
                    "entry #" + (index + 1) + " is not a JSON object (" + JsonFields.render(element)
                            + "); skipped");
            return;
        }
        JsonObject json = element.getAsJsonObject();
        try {
            parseAndRegister(json, index, defaultTargetFallback, fileId, filePriority,
                    fileAlwaysEdible, tally, targetsInFile);
        } catch (Exception e) {
            diagnostics.error(Category.MALFORMED_ENTRY, file,
                    JsonFields.getString(json, KEY_ID, "entry #" + (index + 1)),
                    "could not be parsed (" + e + ") and was skipped; the rest of the file is unaffected");
        }
    }

    private void parseAndRegister(JsonObject json, int index, String defaultTargetFallback,
                                  Identifier fileId, int filePriority, boolean fileAlwaysEdible,
                                  KeyTally tally, Set<String> targetsInFile) {
        String file = fileId.toString();
        String rawTarget;
        try (JsonFields.Scope ignored = JsonFields.reportTo((key, message) -> { })) {
            // Read quietly: a non-string id is reported just below, as the error it is.
            rawTarget = JsonFields.getString(json, KEY_ID, json.has(KEY_ID) ? null : defaultTargetFallback);
        }
        if (rawTarget == null) {
            diagnostics.error(Category.MALFORMED_ENTRY, file, "entry #" + (index + 1),
                    json.has(KEY_ID)
                            ? "'id' is present but not a string; skipped"
                            : "has no 'id'; skipped (only a file holding a single entry may leave it "
                                    + "out, and then the file name is the target)");
            return;
        }
        String target = FoodBuffManager.normalizeTarget(rawTarget);
        boolean fromFileName = !json.has(KEY_ID);

        tally.collect(json, ENTRY_KEYS, "an entry", target);
        validateTarget(target, file, fromFileName);

        if (!targetsInFile.add(target)) {
            diagnostics.warn(Category.DUPLICATE_ENTRY, file, target,
                    "is defined more than once in this file; a higher priority wins, and on a tie "
                            + "the later definition in the file does");
        }

        try (JsonFields.Scope ignored = JsonFields.reportTo(
                (key, message) -> diagnostics.warn(Category.WRONG_TYPE, file, target, message))) {
            int    duration    = JsonFields.getInt(json, KEY_DURATION, 6000);
            // An absent nutrition or saturation means "whatever the eaten item restores on
            // its own", not zero. Both used to default to 0, and the hunger hook applies the
            // entry's numbers in place of the item's — so a pack that only wanted to give the
            // golden apple a buff, and said nothing about hunger, turned it into a food that
            // restored nothing at all. The sentinel is resolved per item at the moment of
            // eating, which is what lets a single #tag or template:default entry keep every
            // food it covers at that food's own value. An explicit value, 0 and negative
            // included, is still taken exactly as written.
            int    nutrition   = JsonFields.getInt(json, KEY_NUTRITION, FoodBuffData.INHERIT_NUTRITION);
            float  saturation  = JsonFields.getFloat(json, KEY_SATURATION, FoodBuffData.INHERIT_SATURATION);
            double healthBonus = JsonFields.getDouble(json, KEY_HEALTH_BONUS, 0.0);
            int    priority    = JsonFields.getInt(json, KEY_PRIORITY, filePriority);

            boolean alwaysEdible = JsonFields.getBoolean(json, KEY_ALWAYS_EDIBLE, fileAlwaysEdible);

            List<FoodBuffData.EffectData> effects = new ArrayList<>();
            if (JsonFields.hasArray(json, KEY_EFFECTS)) {
                for (JsonElement e : json.getAsJsonArray(KEY_EFFECTS)) {
                    if (!e.isJsonObject()) {
                        diagnostics.error(Category.INVALID_EFFECT, file, target,
                                "an item in 'effects' is not an object (" + JsonFields.render(e)
                                        + "); that effect was skipped");
                        continue;
                    }
                    JsonObject effObj = e.getAsJsonObject();
                    tally.collect(effObj, EFFECT_KEYS, "an effect", target);
                    String     rawId  = JsonFields.getString(effObj, KEY_ID);
                    Identifier effId  = rawId == null ? null : Identifier.tryParse(rawId);
                    if (effId == null || !lookup.effectExists(effId)) {
                        diagnostics.error(Category.INVALID_EFFECT, file, target,
                                "lists effect '" + rawId + "', which does not exist; that effect was skipped");
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
                    if (!e.isJsonObject()) {
                        diagnostics.error(Category.INVALID_ATTRIBUTE, file, target,
                                "an item in 'attributes' is not an object (" + JsonFields.render(e)
                                        + "); that attribute was skipped");
                        continue;
                    }
                    JsonObject attrObj = e.getAsJsonObject();
                    tally.collect(attrObj, ATTRIBUTE_KEYS, "an attribute", target);
                    String     rawId   = JsonFields.getString(attrObj, KEY_ATTRIBUTE);
                    Identifier attrId  = rawId == null ? null : Identifier.tryParse(rawId);
                    if (attrId == null || !lookup.attributeExists(attrId)) {
                        diagnostics.error(Category.INVALID_ATTRIBUTE, file, target,
                                "lists attribute '" + rawId + "', which does not exist; that "
                                        + "attribute was skipped");
                        continue;
                    }
                    // An omitted operation is "add_value", the commonest one. A misspelled
                    // one is not: it used to be stored verbatim and turned into add_value at
                    // apply time, so "multiply_total" (the pre-1.20.5 name) silently became a
                    // flat addition. Old names are now understood, and anything else is an
                    // error that costs this one line rather than a wrong number in game.
                    String rawOperation = JsonFields.getString(attrObj, KEY_OPERATION, "add_value");
                    String operation    = AttributeOperations.canonicalize(rawOperation);
                    if (operation == null) {
                        diagnostics.error(Category.UNKNOWN_OPERATION, file, target,
                                "gives attribute '" + rawId + "' the unknown operation '" + rawOperation
                                        + "' (expected one of "
                                        + String.join(", ", AttributeOperations.CANONICAL_NAMES)
                                        + "); that attribute was skipped");
                        continue;
                    }
                    attributes.add(new FoodBuffData.AttributeData(
                            attrId,
                            JsonFields.getDouble(attrObj, KEY_AMOUNT, 0.0),
                            operation));
                }
            }

            FoodBuffData data = new FoodBuffData(
                    target, duration, nutrition, saturation,
                    healthBonus, effects, attributes, priority, alwaysEdible);
            FoodBuffManager.putConfig(target, data, file);
        }
    }

    /**
     * Checks that a target can ever match something. Tag targets are only checked for
     * syntax here — the reload has not bound the new tags yet — and fully in
     * {@link #validateTags}.
     */
    private void validateTarget(String target, String file, boolean fromFileName) {
        String origin = fromFileName ? " (taken from the file name, since the entry has no 'id')" : "";

        if (target.startsWith("#")) {
            if (Identifier.tryParse(target.substring(1)) == null) {
                diagnostics.warn(Category.MISSING_TAG, file, target,
                        "is not a valid tag id; this entry can never apply");
            }
            return;
        }
        if (target.startsWith("namespace:")) return;
        if (target.startsWith("template:")) {
            if (!target.equals("template:default")) {
                diagnostics.warn(Category.MISSING_TARGET, file, target,
                        "is not a recognized template; the only one is 'template:default'");
            }
            return;
        }

        // A potion target names a Potion registry entry, not an item, so it is checked
        // there. It used to pass unchecked, so a typo'd potion id loaded in silence and
        // produced a journal row with no potion that could ever unlock it.
        if (target.startsWith(FoodBuffManager.POTION_PREFIX)) {
            Identifier potionId = Identifier.tryParse(
                    target.substring(FoodBuffManager.POTION_PREFIX.length()));
            if (potionId == null) {
                diagnostics.warn(Category.MISSING_TARGET, file, target,
                        "is not a valid potion id; this entry can never apply");
            } else if (!lookup.potionExists(potionId)) {
                if (lookup.modLoaded(potionId.getNamespace())) {
                    diagnostics.warn(Category.MISSING_TARGET, file, target,
                            "names a potion that does not exist; this entry can never apply");
                } else {
                    absentModTargets++;
                }
            }
            return;
        }

        Identifier targetId = Identifier.tryParse(target);
        if (targetId == null) {
            diagnostics.warn(Category.MISSING_TARGET, file, target,
                    "is not a valid item id" + origin + "; this entry can never apply");
        } else if (!lookup.itemExists(targetId)) {
            if (lookup.modLoaded(targetId.getNamespace())) {
                diagnostics.warn(Category.MISSING_TARGET, file, target,
                        "names an item that does not exist" + origin + "; this entry can never apply");
            } else {
                absentModTargets++;
            }
        } else if (!lookup.itemIsFood(targetId)) {
            // Florafare only ever resolves configs for edible stacks, so an entry for
            // a block, a tool or the potion bottle loads fine and then does nothing.
            diagnostics.warn(Category.NOT_FOOD, file, target,
                    "is not food (it has no food component), so this entry never applies"
                            + (targetId.getPath().endsWith("potion")
                                    ? "; for a brewed potion, target 'potion:<potion id>' instead"
                                    : ""));
        }
    }

    // -------------------------------------------------------------------------
    // PRIORITY
    // -------------------------------------------------------------------------

    /**
     * Turns this reload's priority conflicts into diagnostics and one summary line.
     *
     * <p>A definition that lost to a strictly higher priority is the mechanism doing its
     * job, so it is INFO and logged at DEBUG — but it is also the silent failure behind
     * most "my entry does nothing" reports, because Florafare's bundled files use
     * priorities 1 to 3 and an entry without one is 0. Hence the summary, and the special
     * mention when a pack's entries are losing to the bundled defaults. A tie between two
     * files is a WARNING: which one won was decided by load order, not by anyone.
     */
    private void reportPriorityConflicts() {
        int lost = 0;
        int lostToBundled = 0;
        for (FoodBuffManager.PriorityLoss loss : FoodBuffManager.getPriorityLosses()) {
            if (loss.tie()) {
                // Two definitions in one file were already reported as a duplicate.
                if (loss.file().equals(loss.winnerFile())) continue;
                diagnostics.warn(Category.AMBIGUOUS_TIE, loss.file(), loss.target(),
                        "is also defined by " + loss.winnerFile() + " at the same priority ("
                                + loss.priority() + "); " + loss.winnerFile() + " wins because it loads "
                                + "later (data pack order, then file id). Give one of them a higher "
                                + "priority to make the choice deliberate");
                continue;
            }
            lost++;
            boolean toBundled = isBundled(loss.winnerFile()) && !isBundled(loss.file());
            if (toBundled) lostToBundled++;
            diagnostics.report(DatapackDiagnostics.Severity.INFO, Category.PRIORITY_LOSS,
                    loss.file(), loss.target(),
                    "lost to the definition in " + loss.winnerFile() + " (priority "
                            + loss.winnerPriority() + " beats " + loss.priority() + ")"
                            + (toBundled ? "; Florafare's bundled defaults use priority 1-3, so give "
                                    + "pack entries priority 10 or more" : ""));
        }
        if (lost > 0) {
            Florafare.LOGGER.info(
                    "{} food_buffs entries lost to a higher-priority definition of the same target{}; "
                            + "see /florafare validate.",
                    lost,
                    lostToBundled > 0
                            ? " (" + lostToBundled + " of them to Florafare's bundled defaults, which use "
                                    + "priority 1-3: give pack entries priority 10 or more to override them)"
                            : "");
        }
    }

    /** Whether a source is one of Florafare's own bundled files. */
    private static boolean isBundled(String source) {
        return source != null && source.startsWith(Florafare.MOD_ID + ":");
    }

    // -------------------------------------------------------------------------
    // TAGS
    // -------------------------------------------------------------------------

    /**
     * Reports every {@code #tag} food_buffs target and synergy requirement whose tag is
     * missing or empty. Runs once the reload has bound the new tags (TAGS_LOADED), which
     * is after both listeners — during them the registry still holds the previous
     * reload's tags, or none at all on world load.
     *
     * <p>A tag in a namespace no installed mod owns ({@code c:}, an optional mod's) is
     * only INFO: a pack supporting optional mods legitimately targets tags that nothing
     * fills today. In a loaded mod's namespace it is a WARNING, since there it is almost
     * always a typo.
     */
    public static void validateTags(DatapackLookup lookup) {
        DatapackDiagnostics.Collector tags = DatapackDiagnostics.begin(DatapackDiagnostics.Phase.TAGS);

        List<String> targets = new ArrayList<>();
        for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {
            if (data.target().startsWith("#")) targets.add(data.target());
        }
        targets.sort(null);
        for (String target : targets) {
            checkTag(tags, lookup, target, FoodBuffManager.sourceOf(target), target,
                    "this entry never applies");
        }

        List<FoodSynergyData> synergies = FoodSynergyManager.getAllSynergies();
        synergies.sort(Comparator.comparing(FoodSynergyData::id));
        for (FoodSynergyData synergy : synergies) {
            for (String requirement : synergy.requirements()) {
                if (!requirement.startsWith("#")) continue;
                checkTag(tags, lookup, requirement, FoodSynergyManager.sourceOf(synergy.id()),
                        synergy.id(), "requirement " + requirement + " can never be met, so this "
                                + "synergy never activates");
            }
        }
        tags.publish();
    }

    private static void checkTag(DatapackDiagnostics.Collector tags, DatapackLookup lookup,
                                 String tagTarget, String file, String reportedTarget, String consequence) {
        Identifier tagId = Identifier.tryParse(tagTarget.substring(1));
        if (tagId == null) return; // reported at load
        DatapackLookup.TagState state = lookup.tagState(tagId);
        if (state == DatapackLookup.TagState.PRESENT) return;
        DatapackDiagnostics.Severity severity = lookup.modLoaded(tagId.getNamespace())
                ? DatapackDiagnostics.Severity.WARNING : DatapackDiagnostics.Severity.INFO;
        String what = tagTarget.equals(reportedTarget) ? "this tag" : "tag " + tagTarget;
        if (state == DatapackLookup.TagState.MISSING) {
            tags.report(severity, Category.MISSING_TAG, file, reportedTarget,
                    what + " does not exist; " + consequence);
        } else {
            tags.report(severity, Category.EMPTY_TAG, file, reportedTarget,
                    what + " exists but holds no items; " + consequence);
        }
    }

    // -------------------------------------------------------------------------
    // UNKNOWN KEYS
    // -------------------------------------------------------------------------

    /** Reports the unknown keys of one object directly, for objects that occur once per file. */
    private void reportUnknownKeys(JsonObject json, Set<String> known, String file, String target,
                                   String where) {
        List<String> unknown = JsonFields.unknownKeys(json, known);
        if (unknown.isEmpty()) return;
        diagnostics.warn(Category.UNKNOWN_KEY, file, target,
                "unknown key(s) in " + where + ": " + describeKeys(unknown, known) + "; ignored");
    }

    /** {@code 'healthBonus' (did you mean 'health_bonus'?), 'foo'} */
    static String describeKeys(List<String> keys, java.util.Collection<String> known) {
        List<String> parts = new ArrayList<>(keys.size());
        for (String key : keys) {
            String suggestion = JsonFields.suggestKey(key, known);
            parts.add("'" + key + "'" + (suggestion == null ? "" : " (did you mean '" + suggestion + "'?)"));
        }
        return String.join(", ", parts);
    }

    /**
     * Unknown keys across one file, reported once per key instead of once per entry: a
     * pack that spells {@code healthBonus} in all 300 of its entries has made one
     * mistake, and gets one line naming it with the count.
     */
    static final class KeyTally {
        private record Seen(String where, Set<String> known, String firstTarget, int[] count) {}
        private final Map<String, Seen> seen = new LinkedHashMap<>();

        void collect(JsonObject json, Set<String> known, String where, String target) {
            for (String key : JsonFields.unknownKeys(json, known)) {
                seen.computeIfAbsent(where + "\u0000" + key,
                        ignored -> new Seen(where, known, target, new int[1])).count()[0]++;
            }
        }

        void report(DatapackDiagnostics.Collector diagnostics, String file) {
            for (Map.Entry<String, Seen> entry : seen.entrySet()) {
                Seen info = entry.getValue();
                String key = entry.getKey().substring(entry.getKey().indexOf('\u0000') + 1);
                int count = info.count()[0];
                diagnostics.warn(Category.UNKNOWN_KEY, file, count == 1 ? info.firstTarget() : null,
                        "unknown key " + describeKeys(List.of(key), info.known()) + " in " + info.where()
                                + (count == 1 ? "" : " (" + count + " times in this file, first in '"
                                        + info.firstTarget() + "')")
                                + "; it is ignored");
            }
        }
    }
}
