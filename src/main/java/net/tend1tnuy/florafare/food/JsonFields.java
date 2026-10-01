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
 * <p>Absent and wrong are not the same thing to a pack author, though. An absent field
 * is a choice; {@code "nutrition": "eight"} is a mistake that used to be indistinguishable
 * from it, since both quietly produced the default. So a field that is <em>present</em>
 * but unreadable is also reported to whatever {@link Problems} sink the calling listener
 * installed with {@link #reportTo} — which knows the file and the entry, and turns it into
 * a diagnostic. With no sink installed (commands, tests) the reads behave exactly as
 * before and say nothing.
 *
 * <p>Pure Gson, no Minecraft: registry validation stays with the listeners, and this
 * class can be unit-tested without a game.
 */
public final class JsonFields {

    private JsonFields() {}

    // -------------------------------------------------------------------------
    // PROBLEM REPORTING
    // -------------------------------------------------------------------------

    /** Receives one sentence per field that was present but could not be used as written. */
    @FunctionalInterface
    public interface Problems {
        /**
         * @param key     the field at fault
         * @param message what was wrong and what happened instead, e.g.
         *                {@code 'nutrition' should be a whole number, but is "eight"; ignored}
         */
        void problem(String key, String message);
    }

    /** Ends a {@link #reportTo} scope; no checked exception, so try-with-resources stays tidy. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Per thread rather than a parameter on every read: the listeners make dozens of
     * these calls per entry, and threading a sink through each would have doubled every
     * signature for the sake of one side channel. Reloads run on one thread at a time.
     */
    private static final ThreadLocal<Problems> SINK = new ThreadLocal<>();

    /**
     * Sends every type problem found on this thread to {@code sink} until the returned
     * scope is closed, which restores whatever sink was there before.
     */
    public static Scope reportTo(Problems sink) {
        Problems previous = SINK.get();
        SINK.set(sink);
        return () -> {
            if (previous == null) SINK.remove(); else SINK.set(previous);
        };
    }

    private static void report(String key, String expected, JsonElement found) {
        Problems sink = SINK.get();
        if (sink == null) return;
        sink.problem(key, "'" + key + "' should be " + expected + ", but is " + render(found)
                + "; ignored, so the default applies");
    }

    /** A JSON value as it would appear in the file, cut short if it is long. */
    static String render(JsonElement element) {
        String text = element.toString();
        return text.length() > 40 ? text.substring(0, 37) + "..." : text;
    }

    // -------------------------------------------------------------------------
    // READS
    // -------------------------------------------------------------------------

    /** The element at {@code key}, or null if it is absent or JSON null. */
    private static JsonElement present(JsonObject json, String key) {
        if (json == null || !json.has(key)) return null;
        JsonElement element = json.get(key);
        return (element == null || element.isJsonNull()) ? null : element;
    }

    /**
     * Only a JSON primitive can be a number. Gson's own {@code getAsInt} also accepts a
     * one-element array — {@code "duration": [100]} read as 100 — which is never what
     * anyone meant and is now reported like any other wrong shape.
     */
    private static boolean isScalar(JsonElement element) {
        return element.isJsonPrimitive() && !element.getAsJsonPrimitive().isBoolean();
    }

    private static final java.math.BigDecimal INT_MAX = java.math.BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final java.math.BigDecimal INT_MIN = java.math.BigDecimal.valueOf(Integer.MIN_VALUE);

    public static int getInt(JsonObject json, String key, int fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        if (!isScalar(element)) {
            report(key, "a whole number", element);
            return fallback;
        }
        try {
            // Through BigDecimal so "8.5" is recognized as not whole: Gson's getAsInt
            // truncates it without a word. The truncated value is still what is used —
            // that is what every pack written so far has been getting — but it is said.
            java.math.BigDecimal value = element.getAsBigDecimal();
            if (value.compareTo(INT_MAX) > 0 || value.compareTo(INT_MIN) < 0) {
                // intValue() would wrap a value this size into an unrelated number,
                // negative as often as not.
                report(key, "a whole number between " + Integer.MIN_VALUE + " and "
                        + Integer.MAX_VALUE, element);
                return fallback;
            }
            int whole = value.intValue();
            if (value.stripTrailingZeros().scale() > 0) {
                Problems sink = SINK.get();
                if (sink != null) {
                    sink.problem(key, "'" + key + "' should be a whole number, but is "
                            + render(element) + "; truncated to " + whole);
                }
            }
            return whole;
        } catch (RuntimeException notANumber) {
            report(key, "a whole number", element);
            return fallback;
        }
    }

    public static double getDouble(JsonObject json, String key, double fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        if (!isScalar(element)) {
            report(key, "a number", element);
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException notANumber) {
            report(key, "a number", element);
            return fallback;
        }
    }

    public static float getFloat(JsonObject json, String key, float fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        if (!isScalar(element)) {
            report(key, "a number", element);
            return fallback;
        }
        try {
            return element.getAsFloat();
        } catch (RuntimeException notANumber) {
            report(key, "a number", element);
            return fallback;
        }
    }

    /**
     * A flag. {@code true}/{@code false}, as JSON booleans or as the strings
     * {@code "true"}/{@code "false"} in any case; anything else is the fallback.
     *
     * <p>Gson's own {@code getAsBoolean} reads every string that is not "true" as false,
     * so {@code "always_edible": "yes"} used to switch the flag <em>off</em> without a
     * word. It now keeps the fallback and is reported.
     */
    public static boolean getBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean();
            String text = element.getAsString().trim();
            if (text.equalsIgnoreCase("true"))  return true;
            if (text.equalsIgnoreCase("false")) return false;
        }
        report(key, "true or false", element);
        return fallback;
    }

    /**
     * The string at {@code key}, or {@code fallback}. A non-primitive (an object or an
     * array where a name was expected) yields the fallback rather than Gson's rendering
     * of it, which would otherwise travel on as a nonsense identifier.
     */
    public static String getString(JsonObject json, String key, String fallback) {
        JsonElement element = present(json, key);
        if (element == null) return fallback;
        if (!element.isJsonPrimitive()) {
            report(key, "a string", element);
            return fallback;
        }
        return element.getAsString();
    }

    /** The string at {@code key}, or null when it is missing — for genuinely required ids. */
    public static String getString(JsonObject json, String key) {
        return getString(json, key, null);
    }

    /**
     * True when {@code key} holds an array (empty included). Something else under the key —
     * {@code "effects": {...}} for a single effect — is reported, since the caller is
     * about to treat it as though the field were absent.
     */
    public static boolean hasArray(JsonObject json, String key) {
        JsonElement element = present(json, key);
        if (element == null) return false;
        if (!element.isJsonArray()) {
            report(key, "a list ([ ... ])", element);
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // KEYS
    //
    // An unknown key is the quietest datapack mistake there is: "healthBonus" for
    // "health_bonus", "attribute" for "attributes", "entires" for "entries". The field
    // the author meant is simply absent, so its default applies, and nothing anywhere
    // says so. These two helpers let the listeners name the key and guess the fix.
    // -------------------------------------------------------------------------

    /**
     * The keys of {@code json} that are not in {@code known}, in file order. Keys starting
     * with {@code _} or {@code $} are never reported: {@code _comment} is the documented
     * way to annotate a file, and {@code $schema} is what editors add for validation.
     */
    public static java.util.List<String> unknownKeys(JsonObject json, java.util.Set<String> known) {
        java.util.List<String> unknown = new java.util.ArrayList<>();
        if (json == null) return unknown;
        for (String key : json.keySet()) {
            if (key.startsWith("_") || key.startsWith("$")) continue;
            if (!known.contains(key)) unknown.add(key);
        }
        return unknown;
    }

    /**
     * The known key {@code key} was most likely meant to be, or null when nothing is
     * close. Matches a different spelling of the same words first ({@code healthBonus},
     * {@code Health-Bonus} → {@code health_bonus}), then anything within two edits
     * ({@code entires} → {@code entries}, {@code attribute} → {@code attributes}).
     */
    public static String suggestKey(String key, java.util.Collection<String> known) {
        String squashed = squash(key);
        for (String candidate : known) {
            if (squash(candidate).equals(squashed)) return candidate;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int distance = editDistance(squashed, squash(candidate));
            // Short keys are left alone: "id" is two edits from almost any two-letter word.
            if (distance <= 2 && distance < bestDistance && candidate.length() > 3) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String squash(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
    }

    /** Plain Levenshtein distance; keys are a few characters long, so the quadratic table is nothing. */
    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current  = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
