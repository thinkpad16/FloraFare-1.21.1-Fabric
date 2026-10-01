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
