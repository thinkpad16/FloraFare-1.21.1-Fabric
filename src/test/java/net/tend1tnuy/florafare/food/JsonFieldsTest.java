package net.tend1tnuy.florafare.food;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Datapack fields are hand-written, so every read of one has to survive being wrong.
 *
 * <p>These reads used to be {@code json.get("x").getAsInt()}, and the failure was much
 * worse than a bad value: the NPE escaped to a {@code catch} wrapped around the entire
 * file, taking every entry after the broken one with it and logging nothing that named
 * the culprit. A pack author saw a whole food category quietly stop working.
 */
class JsonFieldsTest {

    private static JsonObject json(String literal) {
        return JsonParser.parseString(literal).getAsJsonObject();
    }

    @Nested
    @DisplayName("a missing field yields the fallback")
    class Missing {

        @Test
        @DisplayName("absent keys never throw")
        void absentKeys() {
            JsonObject empty = json("{}");
            assertEquals(6000, JsonFields.getInt(empty, "duration", 6000));
            assertEquals(0.5, JsonFields.getDouble(empty, "amount", 0.5));
            assertEquals(0.3f, JsonFields.getFloat(empty, "saturation", 0.3f));
            assertTrue(JsonFields.getBoolean(empty, "always_edible", true));
            assertEquals("add_value", JsonFields.getString(empty, "operation", "add_value"));
            assertNull(JsonFields.getString(empty, "id"));
        }

        @Test
        @DisplayName("an explicit JSON null is treated as absent, not as a value")
        void explicitNull() {
            JsonObject withNull = json("{\"duration\": null, \"operation\": null}");
            assertEquals(1200, JsonFields.getInt(withNull, "duration", 1200));
            assertEquals("add_value", JsonFields.getString(withNull, "operation", "add_value"));
        }

        @Test
        @DisplayName("a null object is survivable too")
        void nullObject() {
            assertEquals(7, JsonFields.getInt(null, "duration", 7));
            assertNull(JsonFields.getString(null, "id"));
        }
    }

    @Nested
    @DisplayName("a present field is read")
    class Present {

        @Test
        @DisplayName("numbers and strings come through unchanged")
        void readsValues() {
            JsonObject obj = json(
                    "{\"duration\": 2400, \"amount\": -1.5, \"saturation\": 0.45,"
                            + " \"always_edible\": true, \"operation\": \"add_multiplied_base\"}");
            assertEquals(2400, JsonFields.getInt(obj, "duration", 0));
            assertEquals(-1.5, JsonFields.getDouble(obj, "amount", 0));
            assertEquals(0.45f, JsonFields.getFloat(obj, "saturation", 0));
            assertTrue(JsonFields.getBoolean(obj, "always_edible", false));
            assertEquals("add_multiplied_base", JsonFields.getString(obj, "operation", "add_value"));
        }

        @Test
        @DisplayName("a quoted number is still a number, the way Gson has always read it")
        void quotedNumber() {
            assertEquals(2400, JsonFields.getInt(json("{\"duration\": \"2400\"}"), "duration", 0));
        }
    }

    @Nested
    @DisplayName("a field of the wrong shape yields the fallback rather than throwing")
    class WrongType {

        @Test
        @DisplayName("text where a number belongs")
        void textAsNumber() {
            JsonObject obj = json("{\"duration\": \"soon\"}");
            assertEquals(6000, JsonFields.getInt(obj, "duration", 6000));
        }

        @Test
        @DisplayName("an object where a name belongs does not become its own rendering")
        void objectAsString() {
            JsonObject obj = json("{\"operation\": {\"nested\": true}}");
            assertEquals("add_value", JsonFields.getString(obj, "operation", "add_value"),
                    "Gson would happily hand back \"{\\\"nested\\\":true}\", which would then "
                            + "travel on as an operation name");
        }

        @Test
        @DisplayName("an array where a number belongs")
        void arrayAsNumber() {
            assertEquals(3, JsonFields.getInt(json("{\"amount\": [1,2]}"), "amount", 3));
        }

        @Test
        @DisplayName("an object where a flag belongs")
        void objectAsBoolean() {
            // The file-level "always_edible" default goes through here. Read with a bare
            // getAsBoolean it threw UnsupportedOperationException before a single entry
            // of the file had been looked at, so the whole file was discarded.
            assertTrue(JsonFields.getBoolean(json("{\"always_edible\": {}}"),
                    "always_edible", true));
        }

        @Test
        @DisplayName("a word where a flag belongs keeps the fallback instead of silently meaning false")
        void textAsBoolean() {
            // Gson reads every string but "true" as false, so "yes" used to switch the
            // flag OFF. It is now treated like any other unreadable value.
            assertTrue(JsonFields.getBoolean(json("{\"always_edible\": \"yes\"}"),
                    "always_edible", true));
            assertFalse(JsonFields.getBoolean(json("{\"always_edible\": 1}"),
                    "always_edible", false));
        }

        @Test
        @DisplayName("the words true and false in quotes are still understood")
        void quotedBoolean() {
            assertTrue(JsonFields.getBoolean(json("{\"always_edible\": \"TRUE\"}"),
                    "always_edible", false));
            assertFalse(JsonFields.getBoolean(json("{\"always_edible\": \"false\"}"),
                    "always_edible", true));
        }
    }

    @Nested
    @DisplayName("a present but unusable field is reported")
    class Reporting {

        private final java.util.List<String> problems = new java.util.ArrayList<>();

        private <T> T reading(java.util.function.Supplier<T> read) {
            try (JsonFields.Scope ignored = JsonFields.reportTo((key, message) -> problems.add(message))) {
                return read.get();
            }
        }

        @Test
        @DisplayName("text where a number belongs names the field and the value")
        void wrongTypeIsReported() {
            int value = reading(() -> JsonFields.getInt(json("{\"nutrition\": \"eight\"}"),
                    "nutrition", 4));
            assertEquals(4, value);
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("'nutrition'"), problems.get(0));
            assertTrue(problems.get(0).contains("\"eight\""), problems.get(0));
        }

        @Test
        @DisplayName("an absent field, a null and a correct value report nothing")
        void validReadsAreSilent() {
            reading(() -> JsonFields.getInt(json("{}"), "duration", 1));
            reading(() -> JsonFields.getInt(json("{\"duration\": null}"), "duration", 1));
            reading(() -> JsonFields.getDouble(json("{\"amount\": 2.5}"), "amount", 0));
            reading(() -> JsonFields.getInt(json("{\"duration\": \"2400\"}"), "duration", 0));
            reading(() -> JsonFields.getBoolean(json("{\"always_edible\": true}"), "always_edible", false));
            assertTrue(problems.isEmpty(), problems.toString());
        }

        @Test
        @DisplayName("a fractional whole number is used truncated, as before, but said")
        void fractionIsTruncatedAndReported() {
            assertEquals(8, (int) reading(() -> JsonFields.getInt(json("{\"nutrition\": 8.5}"),
                    "nutrition", 0)));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("truncated to 8"), problems.get(0));
        }

        @Test
        @DisplayName("a number too large for an int is refused rather than wrapped")
        void overflowIsRefused() {
            assertEquals(6000, (int) reading(() -> JsonFields.getInt(
                    json("{\"duration\": 99999999999}"), "duration", 6000)));
            assertEquals(1, problems.size());
        }

        @Test
        @DisplayName("an object where a list belongs is reported by hasArray")
        void objectWhereListBelongs() {
            assertFalse(reading(() -> JsonFields.hasArray(json("{\"effects\": {\"id\": \"x\"}}"),
                    "effects")));
            assertEquals(1, problems.size());
        }

        @Test
        @DisplayName("with no sink installed nothing is reported anywhere")
        void noSinkNoReport() {
            assertEquals(4, JsonFields.getInt(json("{\"nutrition\": \"eight\"}"), "nutrition", 4));
            assertTrue(problems.isEmpty());
        }
    }

    @Nested
    @DisplayName("unknown keys")
    class Keys {

        private final java.util.Set<String> known =
                java.util.Set.of("id", "duration", "health_bonus", "attributes", "entries");

        @Test
        @DisplayName("are listed in file order, comments and $schema excepted")
        void listsUnknownKeys() {
            assertEquals(java.util.List.of("healthBonus", "attribute"),
                    JsonFields.unknownKeys(json("{\"id\": \"a\", \"healthBonus\": 1, "
                            + "\"_comment\": \"x\", \"$schema\": \"y\", \"attribute\": []}"), known));
        }

        @Test
        @DisplayName("get a suggestion for a different spelling or a near miss")
        void suggestions() {
            assertEquals("health_bonus", JsonFields.suggestKey("healthBonus", known));
            assertEquals("health_bonus", JsonFields.suggestKey("Health-Bonus", known));
            assertEquals("attributes", JsonFields.suggestKey("attribute", known));
            assertEquals("entries", JsonFields.suggestKey("entires", known));
            assertNull(JsonFields.suggestKey("flavor_text", known));
        }
    }

    @Nested
    @DisplayName("hasArray")
    class Arrays {

        @Test
        void recognisesArrays() {
            assertTrue(JsonFields.hasArray(json("{\"entries\": []}"), "entries"));
            assertTrue(JsonFields.hasArray(json("{\"entries\": [1]}"), "entries"));
        }

        @Test
        @DisplayName("an object or a missing key is not an array")
        void rejectsNonArrays() {
            assertFalse(JsonFields.hasArray(json("{\"entries\": {}}"), "entries"));
            assertFalse(JsonFields.hasArray(json("{}"), "entries"));
            assertFalse(JsonFields.hasArray(json("{\"entries\": null}"), "entries"));
        }
    }
}
