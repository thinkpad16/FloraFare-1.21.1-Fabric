package net.tend1tnuy.florafare.food;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.config.FlorafareConfig;
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
 * The {@code aftermath} object, through both real listeners: it is held to the same rules
 * as the entry around it, and every way of getting it wrong is reported rather than
 * loaded as something else.
 */
class AftermathParserTest {

    private static final Comparator<Identifier> BY_ID = Comparator.comparing(Identifier::toString);

    private StubLookup lookup;
    private Map<Identifier, JsonElement> files;

    @BeforeEach
    void setUp() {
        lookup = new StubLookup();
        lookup.effects.add("minecraft:nausea");
        lookup.attributes.add("minecraft:generic.attack_damage");
        files = new LinkedHashMap<>();
        FlorafareConfig.autoGenDurationMultiplier = 1200;
        FlorafareConfig.autoGenHealthMultiplier   = 0.5;
    }

    private void file(String id, String json) {
        files.put(Identifier.of(id), JsonParser.parseString(json));
    }

    /** One food entry for bread with the given aftermath object, loaded. */
    private DatapackDiagnostics.Collector bread(String aftermath) {
        file("mypack:drinks", "{\"entries\": [{\"id\": \"minecraft:bread\", \"duration\": 2400,"
                + " \"aftermath\": " + aftermath + "}]}");
        return new FoodReloadListener(lookup).load(files, BY_ID);
    }

    private static FoodAftermath breadAftermath() {
        return FoodBuffManager.getConfigByTarget("minecraft:bread").aftermath();
    }

    private static List<Issue> of(DatapackDiagnostics.Collector report, Category category) {
        return report.issues().stream().filter(issue -> issue.category() == category).toList();
    }

    @Test
    @DisplayName("the README's Arcane Overflow synergy loads with its aftermath")
    void arcaneOverflowSynergy() {
        file("mypack:synergies", """
                {"entries": [{
                  "id": "mypack:arcane_overflow",
                  "requirements": ["minecraft:apple", "minecraft:bread"],
                  "attributes": [{"attribute": "minecraft:generic.attack_damage", "amount": 0.5,
                                  "operation": "add_multiplied_total"}],
                  "aftermath": {
                    "duration": 6000,
                    "attributes": [{"attribute": "minecraft:generic.max_health", "amount": -0.25,
                                    "operation": "add_multiplied_total"}],
                    "effects": [{"id": "minecraft:nausea", "duration": 200}]
                  }
                }]}""");
        DatapackDiagnostics.Collector report = new FoodSynergyReloadListener(lookup).load(files, BY_ID);

        assertTrue(report.issues().isEmpty(), report.issues().toString());
        FoodAftermath aftermath = FoodSynergyManager.getSynergy("mypack:arcane_overflow").aftermath();
        assertEquals(6000, aftermath.duration());
        assertEquals(List.of(new FoodBuffData.AttributeData(
                        Identifier.of("minecraft", "generic.max_health"), -0.25, "add_multiplied_total")),
                aftermath.attributes());
        assertEquals(1, aftermath.effects().size());
    }

    @Test
    @DisplayName("a food buff's aftermath loads, with a legacy operation name canonicalized")
    void foodBuffAftermath() {
        DatapackDiagnostics.Collector report = bread("""
                {"duration": 1200, "health_bonus": -4,
                 "attributes": [{"attribute": "minecraft:generic.max_health", "amount": -0.1,
                                 "operation": "MULTIPLY_TOTAL"}]}""");

        assertTrue(report.issues().isEmpty(), report.issues().toString());
        FoodAftermath aftermath = breadAftermath();
        assertEquals(1200, aftermath.duration());
        assertEquals(-4.0, aftermath.healthBonus());
        assertEquals("add_multiplied_total", aftermath.attributes().get(0).operation(),
                "the shared parser stores the canonical name");
    }

    @Test
    @DisplayName("an unknown operation costs that line, not the aftermath")
    void unknownOperation() {
        DatapackDiagnostics.Collector report = bread("""
                {"duration": 1200, "health_bonus": -2,
                 "attributes": [{"attribute": "minecraft:generic.max_health", "amount": -1,
                                 "operation": "subtract"}]}""");

        assertEquals(1, of(report, Category.UNKNOWN_OPERATION).size());
        assertTrue(breadAftermath().attributes().isEmpty());
        assertEquals(-2.0, breadAftermath().healthBonus());
    }

    @Test
    @DisplayName("an effect or attribute that does not exist is reported and skipped")
    void missingIds() {
        DatapackDiagnostics.Collector report = bread("""
                {"duration": 600,
                 "effects": [{"id": "minecraft:hangover", "duration": 100},
                             {"id": "minecraft:nausea", "duration": 100}],
                 "attributes": [{"attribute": "minecraft:generic.luck_of_the_sea", "amount": 1}]}""");

        assertEquals(1, of(report, Category.INVALID_EFFECT).size());
        assertEquals(1, of(report, Category.INVALID_ATTRIBUTE).size());
        assertEquals(1, breadAftermath().effects().size());
    }

    @Test
    @DisplayName("an aftermath that is not an object is an error, and there is none")
    void notAnObject() {
        DatapackDiagnostics.Collector report = bread("6000");
        assertEquals(1, of(report, Category.INVALID_AFTERMATH).size());
        assertSame(FoodAftermath.NONE, breadAftermath());
        assertNotNull(FoodBuffManager.getConfigByTarget("minecraft:bread"),
                "the entry itself still loads");
    }

    @Test
    @DisplayName("an aftermath with nothing in it is a warning, and there is none")
    void emptyObject() {
        DatapackDiagnostics.Collector report = bread("{\"duration\": 6000}");
        List<Issue> issues = of(report, Category.INVALID_AFTERMATH);
        assertEquals(1, issues.size());
        assertEquals(DatapackDiagnostics.Severity.WARNING, issues.get(0).severity());
        assertSame(FoodAftermath.NONE, breadAftermath());
    }

    @Test
    @DisplayName("modifiers with no duration are an error; the effects are kept")
    void modifiersNeedADuration() {
        DatapackDiagnostics.Collector report = bread("""
                {"duration": 0, "health_bonus": -4,
                 "effects": [{"id": "minecraft:nausea", "duration": 200}]}""");

        List<Issue> issues = of(report, Category.INVALID_AFTERMATH);
        assertEquals(1, issues.size());
        assertEquals(DatapackDiagnostics.Severity.ERROR, issues.get(0).severity());
        FoodAftermath aftermath = breadAftermath();
        assertFalse(aftermath.hasModifiers());
        assertEquals(1, aftermath.effects().size());
        assertFalse(aftermath.isTracked());
    }

    @Test
    @DisplayName("without a duration it lasts as long as its longest finite effect")
    void durationFromEffects() {
        DatapackDiagnostics.Collector report = bread("""
                {"health_bonus": -2,
                 "effects": [{"id": "minecraft:nausea", "duration": 300},
                             {"id": "minecraft:speed", "duration": -1}]}""");

        assertTrue(report.issues().isEmpty(), report.issues().toString());
        assertEquals(300, breadAftermath().duration(), "the infinite effect has no length to lend");
    }

    @Test
    @DisplayName("an effect takes the aftermath's duration, and needs one when there is none")
    void effectDurationDefaults() {
        bread("{\"duration\": 900, \"effects\": [{\"id\": \"minecraft:nausea\"}]}");
        assertEquals(900, breadAftermath().effects().get(0).duration());

        files.clear();
        DatapackDiagnostics.Collector report = bread("{\"effects\": [{\"id\": \"minecraft:nausea\"}]}");
        assertEquals(1, of(report, Category.INVALID_EFFECT).size());
        assertSame(FoodAftermath.NONE, breadAftermath());
    }

    @Test
    @DisplayName("unknown keys inside it are reported with a suggestion")
    void unknownKeys() {
        DatapackDiagnostics.Collector report = bread("""
                {"duration": 600, "healthBonus": -2,
                 "effects": [{"id": "minecraft:nausea", "duration": 100}]}""");
        List<Issue> unknown = of(report, Category.UNKNOWN_KEY);
        assertEquals(1, unknown.size());
        assertTrue(unknown.get(0).message().contains("did you mean 'health_bonus'"), unknown.get(0).message());
    }

    @Test
    @DisplayName("a hunger-only entry is warned that its aftermath can never start")
    void hungerOnlyEntry() {
        file("mypack:snacks", "{\"entries\": [{\"id\": \"minecraft:apple\", \"duration\": 0,"
                + " \"aftermath\": {\"duration\": 600, \"health_bonus\": -2}}]}");
        DatapackDiagnostics.Collector report = new FoodReloadListener(lookup).load(files, BY_ID);
        assertEquals(1, of(report, Category.INVALID_AFTERMATH).size());
    }

    @Test
    @DisplayName("a single-entry file holding only an aftermath is still read as an entry")
    void aftermathMakesASingleEntry() {
        assertEquals(FoodReloadListener.FileShape.SINGLE_ENTRY, FoodReloadListener.shapeOf(
                JsonParser.parseString("{\"aftermath\": {\"duration\": 600, \"health_bonus\": -2}}")));
    }
}
