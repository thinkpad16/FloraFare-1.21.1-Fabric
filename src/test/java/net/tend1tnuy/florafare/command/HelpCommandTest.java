package net.tend1tnuy.florafare.command;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The generated command reference never points at a line the language file lacks. */
class HelpCommandTest {

    @Test
    @DisplayName("every subcommand in /florafarehelp commands has an English description")
    void descriptionsExist() throws Exception {
        try (var in = HelpCommandTest.class.getResourceAsStream("/assets/florafare/lang/en_us.json")) {
            assertNotNull(in);
            JsonObject lang = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            for (String key : HelpCommand.commandDescriptionKeys()) {
                assertTrue(lang.has(key), "missing " + key);
            }
            for (String suffix : new String[] {"short", "title", "levels", "click"}) {
                assertTrue(lang.has("command.florafare.help.commands." + suffix));
            }
        }
    }
}
