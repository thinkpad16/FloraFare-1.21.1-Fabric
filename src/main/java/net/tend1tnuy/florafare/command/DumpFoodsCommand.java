package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.command.dump.FoodDump;
import net.tend1tnuy.florafare.command.dump.FoodDumpCollector;
import net.tend1tnuy.florafare.command.dump.FoodDumpWriter;
import net.tend1tnuy.florafare.command.dump.FoodDumpWriter.Format;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /florafare dumpfoods [modFilter] [json|txt|csv|all] [maxDepth]} — every food in
 * the game, what FloraFare does with it, and every recipe that makes it, written to
 * {@code <game dir>/logs/florafare/dumps/foods-<timestamp>.<ext>}.
 *
 * <p>For building datapacks: the JSON is for scripts, the text for reading, the CSV for a
 * spreadsheet. The format is described in the README; {@link FoodDumpCollector} explains
 * how the data is gathered and {@link net.tend1tnuy.florafare.command.dump.RecipeGraph}
 * how recipes are classified.
 *
 * <h2>Arguments</h2>
 * <ul>
 *   <li>{@code modFilter} — a namespace ({@code farmersdelight}), or {@code *} for every
 *       mod. The format can also follow {@code dumpfoods} directly, for all mods.</li>
 *   <li>format — {@code all} (default), {@code json}, {@code txt} or {@code csv}.</li>
 *   <li>{@code maxDepth} — levels of the text tree, default {@value #DEFAULT_DEPTH}.</li>
 * </ul>
 *
 * <h2>Threads and files</h2>
 * The data is gathered on the server thread, because it reads the registries and the
 * recipe manager, and then handed to the IO pool to be serialized and written, so the
 * megabytes of text are never built while the server is not ticking. Only one dump runs at
 * a time — a second one is refused until the first has written its files, where it used
 * to race the first one into the same fixed file name — and each run writes new files
 * with a timestamp in their name.
 *
 * <p>The write level: it writes into the server's file system.
 */
public final class DumpFoodsCommand {

    private DumpFoodsCommand() {}

    static final int DEFAULT_DEPTH = 3;
    static final int MAX_DEPTH = 10;

    /** The literal that stands for "every mod" in the filter position. */
    static final String ALL_MODS = "*";

    /** Held from the moment a dump starts until its files are written (or it fails). */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    public static ArgumentBuilder<ServerCommandSource, ?> branch() {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("dumpfoods")
                .requires(CommandPermissions::canWrite)
                .executes(context -> execute(context, null, "all", DEFAULT_DEPTH));

        // /florafare dumpfoods <format> [maxDepth] — every mod. Literals, so Brigadier
        // tries them before the free-text filter argument below.
        for (String format : List.of("all", "json", "txt", "csv")) {
            root.then(CommandManager.literal(format)
                    .executes(context -> execute(context, null, format, DEFAULT_DEPTH))
                    .then(depthArgument(context -> execute(context, null, format,
                            IntegerArgumentType.getInteger(context, "maxDepth")))));
        }

        // /florafare dumpfoods <modFilter> [format] [maxDepth]
        root.then(CommandManager.argument("modFilter", StringArgumentType.word())
                .suggests(DumpFoodsCommand::suggestMods)
                .executes(context -> execute(context, filter(context), "all", DEFAULT_DEPTH))
                .then(CommandManager.argument("format", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                List.of("all", "json", "txt", "csv"), builder))
                        .executes(context -> execute(context, filter(context),
                                StringArgumentType.getString(context, "format"), DEFAULT_DEPTH))
                        .then(depthArgument(context -> execute(context, filter(context),
                                StringArgumentType.getString(context, "format"),
                                IntegerArgumentType.getInteger(context, "maxDepth"))))));
        return root;
    }

    private static ArgumentBuilder<ServerCommandSource, ?> depthArgument(
            com.mojang.brigadier.Command<ServerCommandSource> command) {
        return CommandManager.argument("maxDepth", IntegerArgumentType.integer(0, MAX_DEPTH))
                .executes(command);
    }

    private static String filter(CommandContext<ServerCommandSource> context) {
        String raw = StringArgumentType.getString(context, "modFilter");
        return ALL_MODS.equals(raw) ? null : raw.toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code *} and every namespace that adds a food. */
    private static CompletableFuture<Suggestions> suggestMods(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        TreeSet<String> namespaces = new TreeSet<>();
        for (Item item : Registries.ITEM) {
            if (item.getComponents().contains(DataComponentTypes.FOOD)) {
                namespaces.add(Registries.ITEM.getId(item).getNamespace());
            }
        }
        List<String> suggestions = new ArrayList<>();
        suggestions.add(ALL_MODS);
        suggestions.addAll(namespaces);
        return CommandSource.suggestMatching(suggestions, builder);
    }

    // -------------------------------------------------------------------------
    // RUN
    // -------------------------------------------------------------------------

    private static int execute(CommandContext<ServerCommandSource> context, String modFilter,
                               String formatName, int maxDepth) {
        ServerCommandSource source = context.getSource();
        List<Format> formats = Format.parse(formatName);
        if (formats == null) {
            source.sendError(Text.translatable("command.florafare.dumpfoods.bad_format", formatName));
            return 0;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendError(Text.translatable("command.florafare.dumpfoods.busy"));
            return 0;
        }

        FoodDump dump;
        try {
            dump = FoodDumpCollector.collect(source.getServer(),
                    new FoodDumpCollector.Options(modFilter, maxDepth));
        } catch (Exception e) {
            RUNNING.set(false);
            Florafare.LOGGER.error("dumpfoods failed while reading the game's food and recipe data", e);
            source.sendError(Text.translatable("command.florafare.dumpfoods.error", String.valueOf(e.getMessage())));
            return 0;
        }

        if (dump.foods().isEmpty()) {
            RUNNING.set(false);
            source.sendError(Text.translatable("command.florafare.dumpfoods.nothing",
                    modFilter == null ? ALL_MODS : modFilter));
            return 0;
        }

        // Named now, on the server thread, so the summary can say where they went.
        Path dir = ReportFiles.directory("dumps");
        String base = "foods-" + ReportFiles.stampNow() + (modFilter == null ? "" : "-" + modFilter);
        List<Path> files = new ArrayList<>();
        for (Format format : formats) files.add(ReportFiles.uniqueFile(dir, base, format.extension));

        // Auto-open only for the person actually sitting at this machine. On a LAN world
        // any player with the permission level could otherwise pop a text editor onto
        // the host's desktop, and a dedicated server has no desktop to open it on.
        boolean openWhenDone = isLocalHost(source);
        source.sendFeedback(() -> Text.translatable("command.florafare.dumpfoods.started",
                dump.foods().size()).formatted(Formatting.GRAY), false);

        Util.getIoWorkerExecutor().execute(() -> {
            try {
                for (int i = 0; i < formats.size(); i++) {
                    ReportFiles.writeNew(files.get(i), FoodDumpWriter.write(dump, formats.get(i)));
                }
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to write the Florafare food dump into {}", dir, e);
                source.getServer().execute(() -> source.sendError(Text.translatable(
                        "command.florafare.dumpfoods.error", String.valueOf(e.getMessage()))));
                return;
            } finally {
                RUNNING.set(false);
            }

            if (openWhenDone) {
                Path preferred = files.get(0);
                for (Path file : files) if (file.toString().endsWith(".txt")) preferred = file;
                Util.getOperatingSystem().open(preferred.toFile());
            }
            FoodDump.Counts counts = dump.meta().counts();
            Florafare.LOGGER.info("Exported {} foods and {} potion targets ({} recipe outputs, {} unreadable recipes) to {}",
                    counts.foods(), counts.potions(), counts.recipesIndexed(), counts.recipesFailed(), dir);

            source.getServer().execute(() -> {
                source.sendFeedback(() -> Text.translatable("command.florafare.dumpfoods.success",
                        counts.foods(), counts.potions(), counts.recipesIndexed())
                        .formatted(Formatting.GREEN), false);
                if (counts.recipesFailed() > 0) {
                    source.sendFeedback(() -> Text.translatable("command.florafare.dumpfoods.failed_recipes",
                            counts.recipesFailed()).formatted(Formatting.YELLOW), false);
                }
                for (Path file : files) {
                    MutableText line = Text.literal(" • ").formatted(Formatting.DARK_GRAY)
                            .append(ReportFiles.link(file));
                    source.sendFeedback(() -> line, false);
                }
            });
        });
        return dump.foods().size();
    }

    /**
     * Whether the command came from the player running the integrated server — i.e. the
     * one person for whom opening a file in their desktop's default application is a
     * convenience rather than a surprise.
     */
    private static boolean isLocalHost(ServerCommandSource source) {
        if (source.getServer().isDedicated()) return false;
        ServerPlayerEntity player = source.getPlayer();
        // A console/command-block source on an integrated server is the host's own game.
        return player == null || source.getServer().isHost(player.getGameProfile());
    }
}
