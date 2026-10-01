package net.tend1tnuy.florafare.food;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Category;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The food_synergies listener: duplicate ids across files used to overwrite each other in
 * hash order without a word, and a file holding a bare list was skipped just as quietly.
 */
class FoodSynergyReloadListenerTest {

    private static final Comparator<Identifier> BY_ID = Comparator.comparing(Identifier::toString);

    private FoodSynergyReloadListener listener;
    private Map<Identifier, JsonElement> files;

    @BeforeEach
    void setUp() {
        listener = new FoodSynergyReloadListener(new StubLookup());
        files    = new LinkedHashMap<>();
    }

    private void file(String id, String json) {
        files.put(Identifier.of(id), JsonParser.parseString(json));
    }

    private static List<Issue> of(DatapackDiagnostics.Collector report, Category category) {
        return report.issues().stream().filter(issue -> issue.category() == category).toList();
    }

    private static String synergy(String id, int duration) {
        return "{\"id\": \"" + id + "\", \"requirements\": [\"minecraft:apple\", \"minecraft:bread\"],"
                + " \"duration\": " + duration + "}";
    }

    @Test
    @DisplayName("a duplicate id is reported, and the later file wins whatever order the files arrive in")
    void duplicateIdsAreDeterministic() {
        file("zpack:synergies", "{\"entries\": [" + synergy("mypack:picnic", 2) + "]}");
        file("apack:synergies", "{\"entries\": [" + synergy("mypack:picnic", 1) + "]}");
        DatapackDiagnostics.Collector report = listener.load(files, BY_ID);

        assertEquals(2, FoodSynergyManager.getSynergy("mypack:picnic").duration());
        assertEquals("zpack:synergies", FoodSynergyManager.sourceOf("mypack:picnic"));
        List<Issue> duplicates = of(report, Category.DUPLICATE_SYNERGY);
        assertEquals(1, duplicates.size());
        assertTrue(duplicates.get(0).message().contains("apack:synergies"));
    }

    @Test
    @DisplayName("a bare list of synergies loads")
    void topLevelArray() {
        file("mypack:list", "[" + synergy("mypack:one", 10) + "," + synergy("mypack:two", 10) + "]");
        DatapackDiagnostics.Collector report = listener.load(files, BY_ID);

        assertNotNull(FoodSynergyManager.getSynergy("mypack:one"));
        assertNotNull(FoodSynergyManager.getSynergy("mypack:two"));
        assertTrue(report.issues().isEmpty(), report.issues().toString());
    }

    @Test
    @DisplayName("'entries' that is not a list, unknown keys and missing items are reported")
    void problemsAreReported() {
        file("mypack:bad", "{\"entries\": {}}");
        file("mypack:typos", "{\"id\": \"mypack:x\", \"requirments\": [\"minecraft:apple\"],"
                + " \"requirements\": [\"minecraft:aple\"], \"healthBonus\": 2}");
        DatapackDiagnostics.Collector report = listener.load(files, BY_ID);

        assertEquals(1, of(report, Category.MALFORMED_FILE).size());
        List<Issue> unknown = of(report, Category.UNKNOWN_KEY);
        assertEquals(1, unknown.size());
        assertTrue(unknown.get(0).message().contains("did you mean 'requirements'"), unknown.get(0).message());
        assertTrue(unknown.get(0).message().contains("did you mean 'health_bonus'"), unknown.get(0).message());
        assertEquals(1, of(report, Category.INVALID_REQUIREMENT).size());
    }
}
