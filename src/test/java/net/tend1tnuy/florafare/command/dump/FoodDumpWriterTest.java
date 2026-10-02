package net.tend1tnuy.florafare.command.dump;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The three file formats, on a hand-built dump. */
class FoodDumpWriterTest {

    private static FoodDump sample() {
        FoodDump.Flora flora = new FoodDump.Flora("item_entry", "minecraft:bread", "florafare:defaults", 1,
                false, false, 1200, 2.0, 5, 6.0f, false,
                List.of(new FoodDump.Effect("minecraft:speed", 200, 0, 1f)),
                List.of(new FoodDump.Attribute("minecraft:generic.max_health", 2.0, "add_value")),
                new FoodDump.Aftermath(6000, 0.0, List.of(new FoodDump.Effect("minecraft:nausea", 300, 0, 1f)),
                        List.of(new FoodDump.Attribute("minecraft:generic.max_health", -0.25,
                                "add_multiplied_total"))),
                List.of(new FoodDump.Loss("mypack:bread", 0, "florafare:defaults", 1, false)),
                List.of());
        FoodDump.Recipe recipe = new FoodDump.Recipe("minecraft:bread", "minecraft:crafting", 1, null,
                List.of(new FoodDump.Ingredient(List.of("minecraft:wheat"), null, 3, 1)),
                null, null, List.of(), true);
        FoodDump.Food bread = new FoodDump.Food("minecraft:bread", "item", "item.minecraft.bread", "minecraft",
                5, 0.6f, 6.0f, false, 1.6f,
                List.of(new FoodDump.Effect("minecraft:hunger", 600, 0, 0.8f)), null, 64,
                flora, List.of("breakfast"), "minecraft:bread", List.of(recipe), null);
        FoodDump.Meta meta = new FoodDump.Meta(2, "1.21.1", "1.5.0", "2026-10-02T12:00:00Z", null, 3,
                new FoodDump.Counts(1, 0, 1, 0, 1, 0, 1), "none found", List.of("a note"));
        return new FoodDump(meta, List.of(bread), Map.of("minecraft:bread", List.of("└─ 3x minecraft:wheat")));
    }

    @Test
    @DisplayName("JSON: meta and foods, nulls left out, trees not included")
    void json() {
        String json = FoodDumpWriter.json(sample());
        assertTrue(json.contains("\"formatVersion\": 2"));
        assertTrue(json.contains("\"primaryRecipe\": \"minecraft:bread\""));
        assertTrue(json.contains("\"probability\": 0.8"));
        assertTrue(json.contains("\"totalChoices\": 1"));
        assertFalse(json.contains("\"remainder\""), "null fields are omitted");
        assertFalse(json.contains("trees"));
        assertTrue(json.contains("\"aftermath\": {"), "the aftermath sits in the florafare section");
        assertTrue(json.contains("\"operation\": \"add_multiplied_total\""));
    }

    @Test
    @DisplayName("text: values, FloraFare resolution, the losing definition, primary recipe and tree")
    void text() {
        String text = FoodDumpWriter.text(sample());
        assertTrue(text.contains("--- minecraft:bread (item.minecraft.bread)"));
        assertTrue(text.contains("nutrition 5 · saturation 0.6 (6 pts) · eat 1.6s"));
        assertTrue(text.contains("minecraft:hunger I 30s (80%)"));
        assertTrue(text.contains("florafare: item_entry -> minecraft:bread from florafare:defaults (priority 1)"));
        assertTrue(text.contains("lost: mypack:bread (priority 0) to florafare:defaults (priority 1)"));
        assertTrue(text.contains("* minecraft:bread [crafting] <- 3x minecraft:wheat"));
        assertTrue(text.contains("└─ 3x minecraft:wheat"));
        assertTrue(text.contains("aftermath: 6000 ticks · minecraft:generic.max_health -0.25 add_multiplied_total"),
                text);
    }

    @Test
    @DisplayName("CSV: a header, one row per food, cells with commas quoted")
    void csv() {
        String[] rows = FoodDumpWriter.csv(sample()).split("\r\n");
        assertEquals(2, rows.length);
        assertTrue(rows[0].startsWith("id,kind,mod,name_key,nutrition"));
        assertEquals(FoodDumpWriter.CSV_COLUMNS.size(), rows[0].split(",").length);
        assertTrue(rows[1].startsWith("minecraft:bread,item,minecraft,item.minecraft.bread,5,0.6,6,false,1.6,64"));
        assertEquals("\"a, \"\"b\"\"\"", FoodDumpWriter.csvCell("a, \"b\""));
        assertEquals("plain", FoodDumpWriter.csvCell("plain"));
    }

    /** The sample, plus one overdrive synergy in a lockout group and one on its own timer. */
    private static FoodDump withSynergies() {
        FoodDump base = sample();
        FoodDump.Synergy inferno = new FoodDump.Synergy("falleneden:inferno_feast", "falleneden:synergies",
                List.of("falleneden:fried_chicken", "falleneden:red_rum"), 0, "members", 2.0,
                List.of(), List.of(), new FoodDump.Aftermath(2400, -2.0, List.of(), List.of()),
                new FoodDump.Overdrive(3, List.of(
                        new FoodDump.Stage(2, 4.0, List.of(new FoodDump.Effect("minecraft:speed", 0, 1, 1f)),
                                List.of(), new FoodDump.Aftermath(3600, -4.0, List.of(), List.of())),
                        new FoodDump.Stage(3, -2.0, List.of(), List.of(), null))),
                true, "falleneden:all");
        FoodDump.Synergy tea = new FoodDump.Synergy("mypack:tea_time", null, List.of("minecraft:bread"), 6000,
                "own", 0.0, List.of(), List.of(), null, null, false, null);
        return new FoodDump(base.meta(), base.foods(), List.of(inferno, tea), base.trees());
    }

    @Test
    @DisplayName("synergies: timer, ceiling, lockout and every overdrive stage, in JSON and text")
    void synergies() {
        FoodDump dump = withSynergies();
        String json = FoodDumpWriter.json(dump);
        assertTrue(json.contains("\"synergies\": ["), json);
        assertTrue(json.contains("\"timer\": \"own\""));
        assertTrue(json.contains("\"lockoutGroup\": \"falleneden:all\""));
        assertTrue(json.contains("\"stageCount\": 3"));

        String text = FoodDumpWriter.text(dump);
        assertTrue(text.contains("=== SYNERGIES (2) ==="), text);
        assertTrue(text.contains("timer members · no ceiling (lasts as long as its shortest ingredient)"
                + " · lockout group falleneden:all"), text);
        assertTrue(text.contains("timer own · runs 6000 ticks"), text);
        assertTrue(text.contains("overdrive: 3 stages"), text);
        assertTrue(text.contains("stage II: health +4 · effects: minecraft:speed II 0s"), text);
        assertTrue(text.contains("stage III: health -2"), text);
        assertTrue(text.contains("    aftermath: 3600 ticks · health -4"), text);
    }

    @Test
    @DisplayName("format names, and numbers printed the same in every locale")
    void formatsAndNumbers() {
        assertEquals(List.of(FoodDumpWriter.Format.JSON, FoodDumpWriter.Format.TXT, FoodDumpWriter.Format.CSV),
                FoodDumpWriter.Format.parse("ALL"));
        assertEquals(List.of(FoodDumpWriter.Format.CSV), FoodDumpWriter.Format.parse("csv"));
        assertNull(FoodDumpWriter.Format.parse("xml"));
        assertEquals("0.6", FoodDumpWriter.num(0.6f));
        assertEquals("12", FoodDumpWriter.num(12.0));
        assertEquals("0.333", FoodDumpWriter.num(1.0 / 3));
        assertEquals("", FoodDumpWriter.num(null));
    }
}
