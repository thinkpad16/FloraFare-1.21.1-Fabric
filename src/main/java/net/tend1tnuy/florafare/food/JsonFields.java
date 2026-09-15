package net.tend1tnuy.florafare.food;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Forgiving reads of the fields a datapack entry is made of.
 *
 * <p>Both reload listeners used to reach into their JSON with bare
 * {@code json.get("x").getAsInt()} calls for sub-fields they treated as mandatory —
 * an effect's {@code duration}, an attribute's {@code amount} and {@code operation}.
 * Omitting one threw a {@link NullPointerException} out of the middle of parsing, and the
 * only {@code catch} was the one wrapped around the whole <em>file</em>. So a single
 * missing word in one attribute of one entry silently discarded every remaining entry in
 * that file, and the log said nothing more useful than "Failed to parse".
 *
 * <p>Every accessor here answers with the supplied default instead: absent, JSON null, or
 * the wrong type all take the same path. The caller decides what a sensible default is
 * — and, importantly, the caller is then in a position to log which entry was wrong and
 * keep going.
 *
 * <p>Pure Gson, no Minecraft: registry validation stays with the listeners, and this
 * class can be unit-tested without a game.
 */
public final class JsonFields {

    private JsonFields() {}

    /** The element at {@code key}, or null if it is absent or JSON null. */
    private static JsonElement present(JsonObject json, String key) {
        if (json == null || !json.has(key)) return null;
        JsonElement element = json.get(key);
        return (element == null || element.isJsonNull()) ? null : element;
    }

    public static int getInt(JsonObject json, String key, int fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    public static double getDouble(JsonObject json, String key, double fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        try {
            return element.getAsDouble();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    public static float getFloat(JsonObject json, String key, float fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        try {
            return element.getAsFloat();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    public static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return fallback;
        }
    }

    /**
     * The string at {@code key}, or {@code fallback}. A non-primitive (an object or an
     * array where a name was expected) yields the fallback rather than Gson's rendering
     * of it, which would otherwise travel on as a nonsense identifier.
     */
    public static String getString(JsonObject json, String key, String fallback) {
        JsonElement element = present(json, key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        return element.getAsString();
    }

    /** The string at {@code key}, or null when it is missing — for genuinely required ids. */
    public static String getString(JsonObject json, String key) {
        return getString(json, key, null);
    }

    /** True when {@code key} holds a non-empty array. */
    public static boolean hasArray(JsonObject json, String key) {
        JsonElement element = present(json, key);
        return element != null && element.isJsonArray();
    }
}
