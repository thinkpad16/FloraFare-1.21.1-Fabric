package net.tend1tnuy.florafare.command;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Where the commands that write files put them, and how they name them.
 *
 * <p>Everything goes under {@code <game dir>/logs/florafare/<kind>/}, next to the game's own
 * logs and where other tooling (CraftTweaker's {@code crafttweaker.log}) leaves its output,
 * so server hosts find and rotate it with the rest. Each file is named with the moment it
 * was made: the food dump used to write one fixed {@code florafare_edible_items_dump.txt}
 * into the game directory itself, so two dumps overwrote each other — or, run back to
 * back, wrote into the same file at the same time from two IO threads.
 */
public final class ReportFiles {

    private ReportFiles() {}

    /** {@code 20261002-143015}: sorts by time, and is legal in a file name everywhere. */
    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** {@code <game dir>/logs/florafare/<kind>}, not yet created. */
    public static Path directory(String kind) {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("florafare").resolve(kind);
    }

    /** A stamp for files made right now. */
    public static String stampNow() {
        return LocalDateTime.now().format(STAMP);
    }

    /**
     * {@code <dir>/<base>.<extension>}, or {@code <base>-2.<extension>} and so on if a file
     * by that name already exists — two runs inside one second must not overwrite each
     * other.
     */
    public static Path uniqueFile(Path dir, String base, String extension) {
        Path candidate = dir.resolve(base + "." + extension);
        for (int n = 2; Files.exists(candidate) && n < 1000; n++) {
            candidate = dir.resolve(base + "-" + n + "." + extension);
        }
        return candidate;
    }

    /**
     * Writes a whole text file, refusing to replace one that exists. Called on the IO
     * pool, never on the server thread.
     */
    public static void writeNew(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** The file's name, underlined; clicking it copies the full server-side path. */
    public static MutableText link(Path file) {
        String absolute = file.toAbsolutePath().toString();
        return Text.literal(file.getFileName().toString())
                .formatted(Formatting.UNDERLINE, Formatting.AQUA)
                .styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, absolute))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.translatable("command.florafare.file.copy_path", absolute))));
    }
}
