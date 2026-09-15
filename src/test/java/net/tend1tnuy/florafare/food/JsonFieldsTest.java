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
        @DisplayName("a word where a flag belongs follows Gson, which reads anything but "
                + "\"true\" as false")
        void textAsBoolean() {
            assertFalse(JsonFields.getBoolean(json("{\"always_edible\": \"yes\"}"),
                    "always_edible", true),
                    "not the fallback: Gson parses the string rather than rejecting it, and "
                            + "this documents that it is the same reading the mod has always had");
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
