package net.tend1tnuy.florafare;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The language files against each other.
 *
 * <p>Two failures this catches, both of which only show up in game and both of which are
 * easy to introduce while adding a feature: a key added to {@code en_us} and forgotten in
 * {@code uk_ua} (the player sees the raw key), and a translation whose format specifiers
 * do not match the English one (vanilla throws while formatting the line, so the text
 * renders as an error rather than as a sentence).
 */
class LanguageFileTest {

    private static final String SOURCE = "en_us";
    private static final List<String> TRANSLATIONS = List.of("uk_ua");

    private static Map<String, String> load(String language) {
        String path = "/assets/florafare/lang/" + language + ".json";
        try (InputStream in = LanguageFileTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing language file: " + path);
            JsonObject json = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            Map<String, String> entries = new LinkedHashMap<>();
            for (String key : json.keySet()) entries.put(key, json.get(key).getAsString());
            return entries;
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    /** {@code %s} and {@code %1$s} alike; Minecraft accepts both spellings. */
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:(\\d+)\\$)?[sd]");

    /** How many distinct arguments a line consumes. */
    private static int argumentCount(String value) {
        Matcher matcher = FORMAT_SPECIFIER.matcher(value);
        int positional = 0;
        int highestIndexed = 0;
        while (matcher.find()) {
            if (matcher.group(1) == null) positional++;
            else highestIndexed = Math.max(highestIndexed, Integer.parseInt(matcher.group(1)));
        }
        return Math.max(positional, highestIndexed);
    }

    @Test
    @DisplayName("every translation has exactly the keys en_us has")
    void keySetsMatch() {
        Map<String, String> source = load(SOURCE);
        for (String language : TRANSLATIONS) {
            Map<String, String> translation = load(language);

            TreeSet<String> missing = new TreeSet<>(source.keySet());
            missing.removeAll(translation.keySet());
            TreeSet<String> extra = new TreeSet<>(translation.keySet());
            extra.removeAll(source.keySet());

            assertTrue(missing.isEmpty(), language + " is missing keys: " + missing);
            assertTrue(extra.isEmpty(), language + " has keys en_us does not: " + extra);
        }
    }

    @Test
    @DisplayName("a translation takes the same arguments as the English line")
    void formatSpecifiersMatch() {
        Map<String, String> source = load(SOURCE);
        for (String language : TRANSLATIONS) {
            Map<String, String> translation = load(language);
            List<String> mismatches = new ArrayList<>();

            for (Map.Entry<String, String> entry : source.entrySet()) {
                String translated = translation.get(entry.getKey());
                if (translated == null) continue;          // reported by keySetsMatch
                int expected = argumentCount(entry.getValue());
                int actual   = argumentCount(translated);
                if (expected != actual) {
                    mismatches.add(entry.getKey() + " (en_us takes " + expected
                            + ", " + language + " takes " + actual + ")");
                }
            }

            assertTrue(mismatches.isEmpty(),
                    "format specifiers differ, which throws when the line is drawn: " + mismatches);
        }
    }

    @Test
    @DisplayName("a line is blank in every language or in none")
    void blanksAreDeliberate() {
        // Not "nothing is blank": gui.florafare.hud.empty is deliberately empty, because
        // it labels a HUD slot that should draw nothing. What is worth catching is a line
        // blank in one language and written in another, which is a half-finished edit.
        Map<String, String> source = load(SOURCE);
        for (String language : TRANSLATIONS) {
            Map<String, String> translation = load(language);
            List<String> mismatches = new ArrayList<>();

            for (Map.Entry<String, String> entry : source.entrySet()) {
                String translated = translation.get(entry.getKey());
                if (translated == null) continue;          // reported by keySetsMatch
                if (entry.getValue().isBlank() != translated.isBlank()) {
                    mismatches.add(entry.getKey());
                }
            }

            assertTrue(mismatches.isEmpty(),
                    "blank in one language and not the other: " + mismatches);
        }
    }
}
