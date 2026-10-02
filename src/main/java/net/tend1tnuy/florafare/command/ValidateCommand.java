package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.DatapackDiagnostics;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Category;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Issue;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Severity;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code /florafare validate} — everything wrong with the loaded food data, in one place.
 *
 * <p>Before this, the command only re-derived a few synergy problems on the spot, and the
 * datapack listeners' far longer list of findings (wrong field types, typo'd keys, targets
 * naming nothing, entries lost on priority...) lived only in the log, where the line that
 * mattered had scrolled away at startup. It now reads {@link DatapackDiagnostics} and adds
 * what only the live game can tell:
 *
 * <ul>
 *   <li>one section per diagnostics category, with its count;</li>
 *   <li>{@code synergy_reachability} — synergies that need more buffs than there are
 *       slots, and requirements no edible, managed item can satisfy;</li>
 *   <li>{@code priority_loss} and {@code ambiguous_tie} — read from
 *       {@link FoodBuffManager}, so they name the definition in force <em>now</em>
 *       (those two diagnostics categories are left out to avoid printing each twice);</li>
 *   <li>{@code sync} — entries too large to reach clients.</li>
 * </ul>
 *
 * <p>Three forms:
 * <ul>
 *   <li>{@code validate} — the summary: each section's count and its first few lines,
 *       then "…and N more", with the section name clickable;</li>
 *   <li>{@code validate <section> [page]} — one section in full, ten lines a page;</li>
 *   <li>{@code validate file} — the full report written to
 *       {@code <game dir>/logs/florafare/reports/}, for a report too long for chat. The write
 *       level, since it writes into the server's file system.</li>
 * </ul>
 *
 * <p>Every finding in chat can be clicked to copy it, which is how it ends up pasted into
 * a bug report or a datapack's issue tracker rather than retyped.
 */
public final class ValidateCommand {

    private ValidateCommand() {}

    /** Lines of each section the summary shows before "…and N more". */
    static final int SUMMARY_LINES = 3;

    /** Lines per page of {@code validate <section>}. */
    static final int PAGE_SIZE = 10;

    static final String SYNERGY_SECTION   = "synergy_reachability";
    static final String PRIORITY_SECTION  = "priority_loss";
    static final String AMBIGUOUS_SECTION = "ambiguous_tie";
    static final String SYNC_SECTION      = "sync";

    public static ArgumentBuilder<ServerCommandSource, ?> branch() {
        return CommandManager.literal("validate")
                .requires(CommandPermissions::canRead)
                .executes(ValidateCommand::executeSummary)
                // Literal before the section argument, so Brigadier tries it first.
                .then(CommandManager.literal("file")
                        .requires(CommandPermissions::canWrite)
                        .executes(ValidateCommand::executeFile))
                .then(CommandManager.argument("section", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                sectionNames(), builder))
                        .executes(context -> executeSection(context, 1))
                        .then(CommandManager.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> executeSection(context,
                                        IntegerArgumentType.getInteger(context, "page")))));
    }

    // -------------------------------------------------------------------------
    // BUILDING THE REPORT
    // -------------------------------------------------------------------------

    /**
     * Collects every finding. On the server thread: the synergy checks resolve items
     * through the registries and the config map.
     */
    static ValidationReport build() {
        ValidationReport report = new ValidationReport();

        // Datapack diagnostics, grouped by category in the enum's order.
        Map<Category, List<String>> byCategory = new EnumMap<>(Category.class);
        Map<Category, Severity> worst = new EnumMap<>(Category.class);
        for (Issue issue : DatapackDiagnostics.issues()) {
            if (issue.category() == Category.PRIORITY_LOSS
                    || issue.category() == Category.AMBIGUOUS_TIE) continue;
            byCategory.computeIfAbsent(issue.category(), c -> new ArrayList<>())
                    .add(severityTag(issue.severity()) + issue.describe());
            worst.merge(issue.category(), issue.severity(),
                    (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        for (Map.Entry<Category, List<String>> entry : byCategory.entrySet()) {
            report.add(entry.getKey().name(), worst.get(entry.getKey()), entry.getValue());
        }

        report.add(SYNERGY_SECTION, Severity.WARNING, synergyFindings());

        List<String> ambiguous = new ArrayList<>();
        List<String> losses = new ArrayList<>();
        for (FoodBuffManager.PriorityLoss loss : FoodBuffManager.getPriorityLosses()) {
            String line = "'" + loss.target() + "': " + loss.file() + " (priority " + loss.priority()
                    + ") lost to " + loss.winnerFile() + " (priority " + loss.winnerPriority() + ")"
                    + (loss.tie() ? " on a tie — load order decided it" : "");
            (loss.tie() ? ambiguous : losses).add(line);
        }
        // A tie can also be on record with no loss still standing (the file that lost it
        // later won outright); the target list is the authority on what is ambiguous now.
        for (String target : new TreeSet<>(FoodBuffManager.getAmbiguousTargets())) {
            boolean named = false;
            for (String line : ambiguous) named |= line.startsWith("'" + target + "'");
            if (!named) {
                ambiguous.add("'" + target + "': defined by several entries at the same priority; "
                        + "which one wins depends on load order");
            }
        }
        report.add(AMBIGUOUS_SECTION, Severity.WARNING, ambiguous);
        // Informational on purpose: losing on priority is how one pack overrides another.
        report.add(PRIORITY_SECTION, Severity.INFO, losses);

        report.add(SYNC_SECTION, Severity.ERROR, Florafare.syncPayloadIssues());
        return report;
    }

    private static String severityTag(Severity severity) {
        return switch (severity) {
            case ERROR -> "[error] ";
            case WARNING -> "";
            case INFO -> "[info] ";
        };
    }

    /**
     * The synergy checks that need the live game: a synergy needing more buffs than
     * {@code maxBuffSlots} can never fire, and a requirement no managed, edible item
     * satisfies can never be met.
     */
    private static List<String> synergyFindings() {
        List<String> findings = new ArrayList<>();
        int maxSlots = PlayerFoodComponent.getMaxBuffSlots();

        for (FoodSynergyData synergy : FoodSynergyManager.getAllSynergies()) {
            if (synergy.requirements().size() > maxSlots) {
                findings.add(String.format(Locale.ROOT,
                        "synergy '%s' needs %d buffs at once, but maxBuffSlots is %d; it can never activate",
                        synergy.id(), synergy.requirements().size(), maxSlots));
            }
            for (String requirement : synergy.requirements()) {
                String problem = requirementProblem(requirement);
                if (problem != null) {
                    findings.add("synergy '" + synergy.id() + "' requirement '" + requirement + "' " + problem);
                }
            }
        }
        return findings;
    }

    /** Why a requirement can never be met, or null if some managed food meets it. */
    private static String requirementProblem(String requirement) {
        if (requirement.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(requirement.substring(1));
            if (tagId == null) return "is not a valid tag id";
            boolean anyItems = false;
            for (RegistryEntry<Item> entry
                    : Registries.ITEM.iterateEntries(TagKey.of(RegistryKeys.ITEM, tagId))) {
                anyItems = true;
                if (FoodBuffManager.getConfig(entry.value().getDefaultStack()) != null) return null;
            }
            return anyItems
                    ? "matches items, but none of them can grant a buff (all excluded or not edible); it can never be satisfied"
                    : "matches no loaded items; it can never be satisfied";
        }
        if (requirement.startsWith(FoodBuffManager.POTION_PREFIX)) {
            return FoodBuffManager.getConfigByTarget(requirement) != null
                    ? null : "names a potion target no entry defines; it can never be satisfied";
        }
        Identifier itemId = Identifier.tryParse(requirement);
        if (itemId == null || !Registries.ITEM.containsId(itemId)) {
            return "is not a registered item id";
        }
        return FoodBuffManager.getConfig(Registries.ITEM.get(itemId).getDefaultStack()) == null
                ? "can never grant a buff (not edible, or excluded); it can never be satisfied"
                : null;
    }

    /** Every section name a player might type, for tab-completion. */
    private static List<String> sectionNames() {
        List<String> names = new ArrayList<>();
        for (Category category : Category.values()) {
            if (category == Category.PRIORITY_LOSS || category == Category.AMBIGUOUS_TIE) continue;
            names.add(category.name().toLowerCase(Locale.ROOT));
        }
        names.add(SYNERGY_SECTION);
        names.add(PRIORITY_SECTION);
        names.add(AMBIGUOUS_SECTION);
        names.add(SYNC_SECTION);
        return names;
    }

    // -------------------------------------------------------------------------
    // CHAT
    // -------------------------------------------------------------------------

    private static int executeSummary(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ValidationReport report = build();
        int configs = FoodBuffManager.getConfigCount();
        int synergies = FoodSynergyManager.getAllSynergies().size();

        if (report.sections().isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.florafare.validate.clean",
                    configs, synergies).formatted(Formatting.GREEN), false);
            return 0;
        }

        source.sendFeedback(() -> Text.translatable("command.florafare.validate.header",
                report.total(), report.sections().size(), configs, synergies)
                .formatted(Formatting.GOLD), false);

        for (ValidationReport.Section section : report.sections()) {
            source.sendFeedback(() -> sectionHeader(section), false);
            List<String> shown = section.lines().subList(0, Math.min(SUMMARY_LINES, section.size()));
            for (String line : shown) source.sendFeedback(() -> findingLine(line), false);
            int hidden = ValidationReport.hidden(section.size(), shown.size());
            if (hidden > 0) {
                source.sendFeedback(() -> Text.translatable("command.florafare.validate.more", hidden)
                        .formatted(Formatting.DARK_GRAY)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                        "/florafare validate " + section.name()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Text.translatable("command.florafare.validate.open_section")))),
                        false);
            }
        }

        if (CommandPermissions.canWrite(source)) {
            source.sendFeedback(() -> Text.translatable("command.florafare.validate.file_hint")
                    .formatted(Formatting.GRAY, Formatting.UNDERLINE)
                    .styled(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/florafare validate file"))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Text.literal("/florafare validate file")))), false);
        }
        return report.total();
    }

    private static int executeSection(CommandContext<ServerCommandSource> context, int page) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "section").toLowerCase(Locale.ROOT);
        ValidationReport.Section section = build().section(name);

        if (section == null) {
            // A real section with nothing in it is good news, not an error.
            if (sectionNames().contains(name)) {
                source.sendFeedback(() -> Text.translatable("command.florafare.validate.section_clean", name)
                        .formatted(Formatting.GREEN), false);
                return 0;
            }
            source.sendError(Text.translatable("command.florafare.validate.unknown_section", name));
            return 0;
        }

        ValidationReport.Page shown = ValidationReport.page(section.lines(), page, PAGE_SIZE);
        source.sendFeedback(() -> sectionHeader(section)
                .append(Text.translatable("command.florafare.validate.page", shown.page(), shown.pages())
                        .formatted(Formatting.DARK_GRAY)), false);
        for (String line : shown.lines()) source.sendFeedback(() -> findingLine(line), false);

        if (shown.page() < shown.pages()) {
            int next = shown.page() + 1;
            source.sendFeedback(() -> Text.translatable("command.florafare.validate.next_page", next)
                    .formatted(Formatting.GRAY, Formatting.UNDERLINE)
                    .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/florafare validate " + section.name() + " " + next))), false);
        }
        return section.size();
    }

    private static MutableText sectionHeader(ValidationReport.Section section) {
        Formatting color = switch (section.severity()) {
            case ERROR -> Formatting.RED;
            case WARNING -> Formatting.YELLOW;
            case INFO -> Formatting.GRAY;
        };
        return Text.literal("▸ ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(section.name()).formatted(color)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                        "/florafare validate " + section.name()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Text.translatable("command.florafare.validate.open_section")))))
                .append(Text.literal(" (" + section.size() + ") ").formatted(Formatting.GRAY));
    }

    /** One finding; clicking it copies the text. */
    private static Text findingLine(String line) {
        return Text.literal("   • ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(line).formatted(Formatting.WHITE)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, line))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Text.translatable("command.florafare.validate.copy")))));
    }

    // -------------------------------------------------------------------------
    // FILE
    // -------------------------------------------------------------------------

    private static int executeFile(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ValidationReport report = build();

        List<String> header = List.of(
                "Florafare validate report",
                "generated: " + java.time.OffsetDateTime.now(),
                "food configs: " + FoodBuffManager.getConfigCount()
                        + ", synergies: " + FoodSynergyManager.getAllSynergies().size()
                        + ", setbuff stack buffs: " + FoodBuffManager.getRuntimeConfigCount(),
                "findings: " + report.total() + " in " + report.sections().size() + " section(s)");
        // Rendered here, written there: the text is built from data already gathered, and
        // the IO pool must never touch the registries.
        String contents = report.toText(header);
        Path file = ReportFiles.uniqueFile(ReportFiles.directory("reports"),
                "validate-" + ReportFiles.stampNow(), "txt");

        Util.getIoWorkerExecutor().execute(() -> {
            try {
                ReportFiles.writeNew(file, contents);
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to write the Florafare validate report to {}", file, e);
                source.getServer().execute(() -> source.sendError(Text.translatable(
                        "command.florafare.file.error", String.valueOf(e.getMessage()))));
                return;
            }
            source.getServer().execute(() -> source.sendFeedback(() -> Text.translatable(
                    "command.florafare.validate.file_written", report.total())
                    .formatted(Formatting.GREEN)
                    .append(ReportFiles.link(file)), false));
        });
        return report.total();
    }
}
