package net.tend1tnuy.florafare.food;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Category;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Issue;
import net.tend1tnuy.florafare.food.FoodReloadListener.FileShape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The food_buffs listener end to end, minus the registries: which file shapes load,
 * which are reported as malformed, how conflicts between files resolve, and what ends up
 * in the diagnostics report. Driven through {@code load} with a {@link StubLookup}.
 */
class FoodReloadListenerTest {

    /** The same order the real listener falls back to: file id, alphabetically. */
    private static final Comparator<Identifier> BY_ID = Comparator.comparing(Identifier::toString);

    private StubLookup lookup;
    private FoodReloadListener listener;
    private Map<Identifier, JsonElement> files;

    @BeforeEach
    void setUp() {
        lookup   = new StubLookup();
        listener = new FoodReloadListener(lookup);
        files    = new LinkedHashMap<>();
        FlorafareConfig.autoGenDurationMultiplier = 1200;
        FlorafareConfig.autoGenHealthMultiplier   = 0.5;
    }

    private void file(String id, String json) {
        files.put(Identifier.of(id), JsonParser.parseString(json));
    }

    private DatapackDiagnostics.Collector load() {
        return listener.load(files, BY_ID);
    }

    private static List<Issue> of(DatapackDiagnostics.Collector report, Category category) {
        return report.issues().stream().filter(issue -> issue.category() == category).toList();
    }

    @Nested
    @DisplayName("file shapes")
    class Shapes {

        @Test
        @DisplayName("a single entry with no id takes its target from the file name")
        void idLessSingleEntry() {
            file("mypack:meats/beef", "{\"duration\": 1234}");
            DatapackDiagnostics.Collector report = load();

            FoodBuffData data = FoodBuffManager.getConfigByTarget("mypack:beef");
            assertNotNull(data, "the id-less form used to be unreachable and load nothing");
            assertEquals(1234, data.duration());
            assertTrue(report.issues().isEmpty(), report.issues().toString());
        }

        @Test
        @DisplayName("a single entry with an id still uses the id")
        void singleEntryWithId() {
            file("mypack:whatever", "{\"id\": \"minecraft:bread\", \"duration\": 50}");
            load();
            assertEquals(50, FoodBuffManager.getConfigByTarget("minecraft:bread").duration());
            assertNull(FoodBuffManager.getConfigByTarget("mypack:whatever"));
        }

        @Test
        @DisplayName("a misspelled 'entries' is a malformed file, not an entry named after the file")
        void misspelledEntries() {
            file("mypack:meats", "{\"priority\": 5, \"entires\": [{\"id\": \"minecraft:beef\"}]}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(0, FoodBuffManager.getConfigCount());
            List<Issue> malformed = of(report, Category.MALFORMED_FILE);
            assertEquals(1, malformed.size());
            assertTrue(malformed.get(0).message().contains("did you mean 'entries'"),
                    malformed.get(0).message());
        }

        @Test
        @DisplayName("'entries' holding an object rather than a list is reported")
        void entriesAsObject() {
            file("mypack:meats", "{\"entries\": {\"id\": \"minecraft:beef\"}}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(0, FoodBuffManager.getConfigCount());
            assertEquals(1, of(report, Category.MALFORMED_FILE).size());
        }

        @Test
        @DisplayName("an object with only file-level settings matches neither shape")
        void onlyFileSettings() {
            file("mypack:empty", "{\"priority\": 3}");
            assertEquals(1, of(load(), Category.MALFORMED_FILE).size());
        }

        @Test
        @DisplayName("the classifier, case by case")
        void classifier() {
            assertEquals(FileShape.ENTRIES, FoodReloadListener.shapeOf(JsonParser.parseString("{\"entries\": []}")));
            assertEquals(FileShape.LEGACY_ARRAY, FoodReloadListener.shapeOf(JsonParser.parseString("[]")));
            assertEquals(FileShape.SINGLE_ENTRY, FoodReloadListener.shapeOf(JsonParser.parseString("{\"nutrition\": 3}")));
            assertEquals(FileShape.ENTRIES_NOT_A_LIST, FoodReloadListener.shapeOf(JsonParser.parseString("{\"entries\": 3}")));
            assertEquals(FileShape.UNRECOGNIZED, FoodReloadListener.shapeOf(JsonParser.parseString("{\"Entries\": []}")));
            assertEquals(FileShape.NOT_AN_OBJECT, FoodReloadListener.shapeOf(JsonParser.parseString("\"text\"")));
        }

        @Test
        @DisplayName("an entry in a list without an id is skipped with an error, the rest still load")
        void idLessEntryInAList() {
            file("mypack:foods", "{\"entries\": [{\"duration\": 5}, {\"id\": \"minecraft:apple\"}]}");
            DatapackDiagnostics.Collector report = load();

            assertNotNull(FoodBuffManager.getConfigByTarget("minecraft:apple"));
            assertEquals(1, of(report, Category.MALFORMED_ENTRY).size());
        }
    }

    @Nested
    @DisplayName("conflicts between files")
    class Conflicts {

        @Test
        @DisplayName("an equal-priority tie goes to the later file id, whatever order the files arrive in")
        void tieIsDeterministic() {
            // Inserted in reverse, so a winner decided by arrival order would differ.
            file("zpack:bread", "{\"entries\": [{\"id\": \"minecraft:bread\", \"duration\": 2}]}");
            file("apack:bread", "{\"entries\": [{\"id\": \"minecraft:bread\", \"duration\": 1}]}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(2, FoodBuffManager.getConfigByTarget("minecraft:bread").duration());
            assertEquals("zpack:bread", FoodBuffManager.sourceOf("minecraft:bread"));
            assertTrue(FoodBuffManager.getAmbiguousTargets().contains("minecraft:bread"));
            List<Issue> ties = of(report, Category.AMBIGUOUS_TIE);
            assertEquals(1, ties.size());
            assertEquals("apack:bread", ties.get(0).file());
        }

        @Test
        @DisplayName("an entry without a priority losing to the bundled defaults is reported, with the fix")
        void lossToBundledIsReported() {
            file("florafare:breads_and_baked",
                    "{\"priority\": 1, \"entries\": [{\"id\": \"minecraft:bread\", \"duration\": 1}]}");
            file("mypack:bread", "{\"entries\": [{\"id\": \"minecraft:bread\", \"duration\": 999}]}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(1, FoodBuffManager.getConfigByTarget("minecraft:bread").duration());
            List<Issue> losses = of(report, Category.PRIORITY_LOSS);
            assertEquals(1, losses.size());
            assertEquals("mypack:bread", losses.get(0).file());
            assertEquals(DatapackDiagnostics.Severity.INFO, losses.get(0).severity());
            assertTrue(losses.get(0).message().contains("florafare:breads_and_baked"));
            assertTrue(losses.get(0).message().contains("priority 10"), losses.get(0).message());
        }

        @Test
        @DisplayName("a duplicate inside one file is reported once, and its last definition wins")
        void duplicateInsideAFile() {
            file("mypack:foods", "{\"entries\": ["
                    + "{\"id\": \"minecraft:apple\", \"duration\": 1},"
                    + "{\"id\": \"minecraft:apple\", \"duration\": 2}]}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(2, FoodBuffManager.getConfigByTarget("minecraft:apple").duration());
            assertEquals(1, of(report, Category.DUPLICATE_ENTRY).size());
            assertTrue(of(report, Category.AMBIGUOUS_TIE).isEmpty());
            assertFalse(FoodBuffManager.getAmbiguousTargets().contains("minecraft:apple"));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a missing item and a non-food item are both reported; an absent mod's is not")
        void targets() {
            file("mypack:foods", "{\"entries\": ["
                    + "{\"id\": \"minecraft:breadd\"},"
                    + "{\"id\": \"minecraft:stone\"},"
                    + "{\"id\": \"minecraft:potion\"},"
                    + "{\"id\": \"absentmod:pie\"},"
                    + "{\"id\": \"potion:minecraft:strenght\"},"
                    + "{\"id\": \"template:fallback\"}]}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(3, of(report, Category.MISSING_TARGET).size(),
                    "breadd, the misspelled potion and the unknown template");
            List<Issue> notFood = of(report, Category.NOT_FOOD);
            assertEquals(2, notFood.size());
            assertTrue(notFood.get(1).message().contains("potion:"), "the potion bottle gets the hint");
        }

        @Test
        @DisplayName("a field of the wrong type is reported and falls back to its default")
        void wrongType() {
            file("mypack:foods", "{\"entries\": [{\"id\": \"minecraft:apple\", \"nutrition\": \"eight\"}]}");
            DatapackDiagnostics.Collector report = load();

            assertFalse(FoodBuffManager.getConfigByTarget("minecraft:apple").hasNutrition(),
                    "an unreadable nutrition keeps the item's own, like an absent one");
            List<Issue> wrong = of(report, Category.WRONG_TYPE);
            assertEquals(1, wrong.size());
            assertEquals("minecraft:apple", wrong.get(0).target());
            assertEquals("mypack:foods", wrong.get(0).file());
        }

        @Test
        @DisplayName("an unknown key repeated across a file is reported once, with a count and a suggestion")
        void unknownKeysAreAggregated() {
            file("mypack:foods", "{\"entries\": ["
                    + "{\"id\": \"minecraft:apple\", \"healthBonus\": 2},"
                    + "{\"id\": \"minecraft:bread\", \"healthBonus\": 2},"
                    + "{\"id\": \"minecraft:beef\", \"healthBonus\": 2, \"_comment\": \"fine\"}]}");
            DatapackDiagnostics.Collector report = load();

            List<Issue> unknown = of(report, Category.UNKNOWN_KEY);
            assertEquals(1, unknown.size(), unknown.toString());
            assertTrue(unknown.get(0).message().contains("did you mean 'health_bonus'"));
            assertTrue(unknown.get(0).message().contains("3 times"));
        }

        @Test
        @DisplayName("a bad effect, attribute or operation costs that line and is reported")
        void badLines() {
            file("mypack:foods", "{\"entries\": [{\"id\": \"minecraft:apple\","
                    + "\"effects\": [{\"id\": \"minecraft:sped\"}, {\"id\": \"minecraft:speed\"}],"
                    + "\"attributes\": [{\"attribute\": \"minecraft:generic.max_health\", \"amount\": 1,"
                    + " \"operation\": \"times\"}]}]}");
            DatapackDiagnostics.Collector report = load();

            FoodBuffData apple = FoodBuffManager.getConfigByTarget("minecraft:apple");
            assertEquals(1, apple.effects().size());
            assertTrue(apple.attributes().isEmpty());
            assertEquals(1, of(report, Category.INVALID_EFFECT).size());
            assertEquals(1, of(report, Category.UNKNOWN_OPERATION).size());
        }
    }

    @Nested
    @DisplayName("config_generation.json")
    class ConfigGeneration {

        @Test
        @DisplayName("values are clamped to the config file's ranges")
        void clamped() {
            file("mypack:config_generation", "{\"health_multiplier\": -1, \"duration_multiplier\": 999999999}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(0.0, FoodBuffManager.AUTO_GEN_HEALTH_MULT,
                    "a negative multiplier made eating lower max health");
            assertEquals(FlorafareConfig.MAX_AUTO_GEN_DURATION_MULT, FoodBuffManager.AUTO_GEN_DURATION_MULT);
            assertEquals(2, of(report, Category.CONFIG_GENERATION).size());
        }

        @Test
        @DisplayName("several files apply field by field in a fixed order, and are reported")
        void severalFiles() {
            file("zpack:config_generation", "{\"health_multiplier\": 2.0}");
            file("apack:config_generation", "{\"health_multiplier\": 1.0, \"duration_multiplier\": 600}");
            DatapackDiagnostics.Collector report = load();

            assertEquals(2.0, FoodBuffManager.AUTO_GEN_HEALTH_MULT, "the later file wins the field it sets");
            assertEquals(600, FoodBuffManager.AUTO_GEN_DURATION_MULT, "and the earlier keeps the one it alone sets");
            assertEquals(1, of(report, Category.CONFIG_GENERATION).size());
        }

        @Test
        @DisplayName("with no file the config's own values apply")
        void seededFromConfig() {
            FlorafareConfig.autoGenDurationMultiplier = 300;
            load();
            assertEquals(300, FoodBuffManager.AUTO_GEN_DURATION_MULT);
        }
    }

    @Nested
    @DisplayName("tags and the published report")
    class TagsAndReport {

        @Test
        @DisplayName("missing and empty tag targets are reported once tags are bound")
        void tagValidation() {
            FoodSynergyManager.clear();
            lookup.tags.put("minecraft:full", DatapackLookup.TagState.PRESENT);
            lookup.tags.put("minecraft:hollow", DatapackLookup.TagState.EMPTY);
            file("mypack:tags", "{\"entries\": [{\"id\": \"#minecraft:full\"},"
                    + "{\"id\": \"#minecraft:hollow\"}, {\"id\": \"#minecraft:nowhere\"},"
                    + "{\"id\": \"#c:optional_thing\"}]}");
            load();
            FoodReloadListener.validateTags(lookup);

            assertEquals(1, DatapackDiagnostics.issues(Category.EMPTY_TAG).size());
            List<Issue> missing = DatapackDiagnostics.issues(Category.MISSING_TAG);
            assertEquals(2, missing.size());
            // c: belongs to no installed mod, so a pack may target it for optional support.
            Issue optional = missing.stream()
                    .filter(issue -> "#c:optional_thing".equals(issue.target())).findFirst().orElseThrow();
            assertEquals(DatapackDiagnostics.Severity.INFO, optional.severity());
            assertEquals("mypack:tags", optional.file());
        }

        @Test
        @DisplayName("publishing replaces the previous load's issues instead of piling up")
        void publishReplaces() {
            file("mypack:broken", "\"nope\"");
            load().publish();
            assertEquals(1, DatapackDiagnostics.issues(Category.MALFORMED_FILE).size());

            files.clear();
            load().publish();
            assertTrue(DatapackDiagnostics.issues(Category.MALFORMED_FILE).isEmpty());
        }
    }
}
