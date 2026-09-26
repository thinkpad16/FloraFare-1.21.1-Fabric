package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodExclusions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /florafare ignore} — the in-game half of the exclusion list.
 *
 * <p>The list can always be edited by hand in {@code florafare.json}, but that means a
 * server restart and a text editor for what is usually a one-line decision made while
 * standing in front of the item ("this mod's drinks already do their own thing"). This
 * branch does it live: the rule takes effect on the next bite, is re-sent to every
 * player online so their tooltips and hunger prediction stop disagreeing with the
 * server, and is written back into the config file so it survives a restart.
 *
 * <p>{@code remove} answers in one of two ways, and the difference is worth knowing:
 *
 * <ul>
 *   <li>if the rule is one this config file owns, it is deleted;</li>
 *   <li>if it came from the {@code #florafare:ignored} tag or from another mod's API
 *       call — neither of which this command can edit — an <em>allow-list</em> rule is
 *       written instead, which beats the exclusion. Same outcome for the player, and it
 *       survives the next datapack reload, which deleting from a tag could not.</li>
 * </ul>
 *
 * <p>{@code add} is its mirror: it drops an allow-list rule if one is standing in the
 * way, and otherwise writes an ordinary exclusion.
 *
 * <p>Both take an {@code inventory} form as well, which applies the same operation to
 * every food a player is carrying — see {@link #applyInventory}.
 */
public final class IgnoreCommand {

    private IgnoreCommand() {}

    /** The {@code ignore} subtree, for {@code SetBuffCommand} to hang off {@code /florafare}. */
    public static ArgumentBuilder<ServerCommandSource, ?> branch() {
        return CommandManager.literal("ignore")
                .executes(IgnoreCommand::executeList)
                .then(CommandManager.literal("list")
                        .executes(IgnoreCommand::executeList))
                .then(CommandManager.literal("add")
                        .then(inventoryBranch(true))
                        .then(CommandManager.argument("entry", StringArgumentType.greedyString())
                                .suggests(IgnoreCommand::suggestAdditions)
                                .executes(IgnoreCommand::executeAdd)))
                .then(CommandManager.literal("remove")
                        .then(inventoryBranch(false))
                        .then(CommandManager.argument("entry", StringArgumentType.greedyString())
                                .suggests(IgnoreCommand::suggestRemovals)
                                .executes(IgnoreCommand::executeRemove)));
    }

    /**
     * {@code add inventory [<player>]} / {@code remove inventory [<player>]}.
     *
     * <p>A literal sibling of the greedy {@code entry} argument. Brigadier matches
     * literals first, so a mod whose id is genuinely {@code inventory} would be shadowed
     * here — it is still reachable as {@code inventory:*}, which the literal does not
     * match, and the trade is worth it for a word this useful.
     *
     * <p>The player argument is optional and rarely used, but it is what lets the command
     * run from the console or a command block, where there is no "you" to read.
     */
    private static ArgumentBuilder<ServerCommandSource, ?> inventoryBranch(boolean exclude) {
        return CommandManager.literal("inventory")
                .executes(context -> applyInventory(
                        context.getSource(), context.getSource().getPlayerOrThrow(), exclude))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> applyInventory(
                                context.getSource(),
                                EntityArgumentType.getPlayer(context, "player"), exclude)));
    }

    // -------------------------------------------------------------------------
    // LIST
    // -------------------------------------------------------------------------

    private static int executeList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        List<String> editable = FoodExclusions.canonicalEntries(
                FoodExclusions.Effect.EXCLUDE, FoodExclusions.Source.CONFIG);
        List<String> fromMods = FoodExclusions.canonicalEntries(
                FoodExclusions.Effect.EXCLUDE, FoodExclusions.Source.API);
        List<String> all      = FoodExclusions.canonicalEntries(FoodExclusions.Effect.EXCLUDE);
        List<String> managed  = FoodExclusions.canonicalEntries(FoodExclusions.Effect.MANAGE);

        if (all.isEmpty() && managed.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.florafare.ignore.empty")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }

        source.sendFeedback(() -> Text.translatable("command.florafare.ignore.header", all.size())
                .formatted(Formatting.GOLD), false);

        for (String entry : editable) {
            source.sendFeedback(() -> line(entry, null), false);
        }
        for (String entry : fromMods) {
            source.sendFeedback(() -> line(entry, "command.florafare.ignore.from_mod"), false);
        }
        // Everything left is the built-in tag (and any tag the config named, already
        // printed above) — listed because an admin asking "why is this item skipped"
        // needs to see the rule even when they cannot edit it from here.
        for (String entry : all) {
            if (editable.contains(entry) || fromMods.contains(entry)) continue;
            source.sendFeedback(() -> line(entry, "command.florafare.ignore.builtin"), false);
        }

        // The allow-list gets its own heading rather than being folded in above: these
        // rules do the opposite of everything printed so far, and a reader skimming one
        // flat list would take them for more exclusions.
        if (!managed.isEmpty()) {
            source.sendFeedback(() -> Text.translatable(
                    "command.florafare.ignore.managed_header", managed.size())
                    .formatted(Formatting.GOLD), false);
            for (String entry : managed) {
                source.sendFeedback(() -> line(entry, null), false);
            }
        }
        return all.size();
    }

    private static Text line(String entry, String noteKey) {
        var text = Text.literal(" • ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(entry).formatted(Formatting.WHITE));
        if (noteKey != null) {
            text.append(Text.literal(" ").append(Text.translatable(noteKey))
                    .formatted(Formatting.DARK_GRAY));
        }
        return text;
    }

    // -------------------------------------------------------------------------
    // ONE RULE
    //
    // The single-entry commands and the bulk inventory forms share these two, so the
    // two paths cannot drift into disagreeing about what "add" and "remove" mean.
    // Neither saves or re-syncs: a bulk run must do that once at the end, not per item.
    // -------------------------------------------------------------------------

    /** What {@link #addOne} did. */
    private enum AddOutcome {
        /** An exclusion rule was written. */
        EXCLUDED,
        /** An allow-list rule was standing in the way and was dropped. */
        OVERRIDE_LIFTED,
        /** Both were already in the wanted state. */
        ALREADY
    }

    private static AddOutcome addOne(FoodExclusions.Entry parsed) {
        String canonical = parsed.canonical();

        // An allow-list rule standing in the way goes first. Adding an exclusion on top
        // of one that beats it would report success and change nothing.
        boolean liftedOverride = FoodExclusions.remove(
                FoodExclusions.Effect.MANAGE, FoodExclusions.Source.CONFIG, canonical) != null;
        if (liftedOverride) removeAllSpellings(FlorafareConfig.managedFoodItems, parsed);

        boolean excluded = FoodExclusions.add(
                FoodExclusions.Effect.EXCLUDE, FoodExclusions.Source.CONFIG, canonical) != null;
        if (excluded) {
            // The config lists are the file's view of the same rules, so they are edited
            // to match rather than re-derived: a bare "modid" the user hand-wrote must not
            // be rewritten into "modid:*" underneath them on an unrelated save.
            if (parsed.kind() == FoodExclusions.Kind.MOD) {
                FlorafareConfig.ignoredFoodMods.add(parsed.value());
            } else {
                FlorafareConfig.ignoredFoodItems.add(canonical);
            }
        }

        if (excluded) return AddOutcome.EXCLUDED;
        return liftedOverride ? AddOutcome.OVERRIDE_LIFTED : AddOutcome.ALREADY;
    }

    /** What {@link #removeOne} did. */
    private enum RemoveOutcome {
        /** This config file's own rule was deleted. */
        DELETED,
        /** The rule belongs to a tag or another mod; an allow-list rule now beats it. */
        OVERRIDDEN,
        /** Nothing was excluding it in the first place. */
        NOT_LISTED
    }

    private static RemoveOutcome removeOne(FoodExclusions.Entry parsed) {
        String canonical = parsed.canonical();

        if (FoodExclusions.remove(
                FoodExclusions.Effect.EXCLUDE, FoodExclusions.Source.CONFIG, canonical) != null) {
            // Every spelling of the same rule goes, not just the one that was typed: the
            // file may say "loot_n_explore" where the command said "loot_n_explore:*",
            // and leaving the other one behind would resurrect the rule on the next
            // config load.
            removeAllSpellings(FlorafareConfig.ignoredFoodItems, parsed);
            removeAllSpellings(FlorafareConfig.ignoredFoodMods, parsed);
            return RemoveOutcome.DELETED;
        }

        // Not one of ours to delete — it came from the #florafare:ignored tag or from
        // another mod. Deleting from a tag is not a thing vanilla can do, and unpicking
        // another mod's API call would be undone at its next startup, so the answer is an
        // allow-list rule that beats the exclusion and survives both.
        if (FoodExclusions.add(
                FoodExclusions.Effect.MANAGE, FoodExclusions.Source.CONFIG, canonical) == null) {
            return RemoveOutcome.NOT_LISTED;
        }
        FlorafareConfig.managedFoodItems.add(canonical);
        return RemoveOutcome.OVERRIDDEN;
    }

    // -------------------------------------------------------------------------
    // ADD / REMOVE — one written rule
    // -------------------------------------------------------------------------

    private static int executeAdd(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String raw = StringArgumentType.getString(context, "entry").trim();

        FoodExclusions.Entry parsed = FoodExclusions.parse(raw);
        if (parsed == null) {
            source.sendError(Text.translatable("command.florafare.ignore.invalid", raw));
            return 0;
        }

        if (addOne(parsed) == AddOutcome.ALREADY) {
            source.sendError(Text.translatable("command.florafare.ignore.already", parsed.canonical()));
            return 0;
        }

        persistAndResync(source);
        source.sendFeedback(() -> Text.translatable("command.florafare.ignore.added",
                Text.literal(parsed.canonical()).formatted(Formatting.WHITE)), true);
        return 1;
    }

    private static int executeRemove(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String raw = StringArgumentType.getString(context, "entry").trim();

        FoodExclusions.Entry parsed = FoodExclusions.parse(raw);
        if (parsed == null) {
            source.sendError(Text.translatable("command.florafare.ignore.invalid", raw));
            return 0;
        }

        RemoveOutcome outcome = removeOne(parsed);
        if (outcome == RemoveOutcome.NOT_LISTED) {
            source.sendError(Text.translatable(
                    "command.florafare.ignore.not_listed", parsed.canonical()));
            return 0;
        }

        persistAndResync(source);
        source.sendFeedback(() -> Text.translatable(
                outcome == RemoveOutcome.DELETED
                        ? "command.florafare.ignore.removed"
                        : "command.florafare.ignore.managed_again",
                Text.literal(parsed.canonical()).formatted(Formatting.WHITE)), true);
        return 1;
    }

    // -------------------------------------------------------------------------
    // ADD / REMOVE — a whole inventory
    // -------------------------------------------------------------------------

    /** How many item lines the summary prints before collapsing the rest into a count. */
    private static final int MAX_LISTED = 20;

    /**
     * Applies {@code add} or {@code remove} to every food a player is carrying.
     *
     * <p>For the case the single-entry form is tedious for: standing in front of a chest
     * of another mod's drinks, grabbing a stack of each, and taking the whole lot out of
     * Florafare's hands in one command instead of thirty-five.
     *
     * <p>Three things it deliberately does <em>not</em> do:
     *
     * <ul>
     *   <li><b>Non-food is skipped.</b> Florafare never looks at anything without a food
     *       component, so a rule naming a sword or a stack of cobblestone would sit in
     *       the config forever doing nothing. The count of what was skipped is reported,
     *       so the number of rules written is never a mystery.</li>
     *   <li><b>Items already in the wanted state are skipped</b> rather than re-written.
     *       Without that, running this while carrying the drinks Florafare already ships
     *       exclusions for would copy dozens of redundant lines into the config file.</li>
     *   <li><b>Nothing is written per item.</b> The config is saved and the clients are
     *       re-synced once, after the whole inventory has been walked.</li>
     * </ul>
     *
     * @param exclude true to ignore everything carried, false to manage it all again
     * @return how many items actually changed
     */
    private static int applyInventory(ServerCommandSource source, ServerPlayerEntity player,
                                      boolean exclude) {
        InventoryScan scan = scanInventory(player);
        String playerName = player.getName().getString();

        if (scan.foods().isEmpty()) {
            source.sendError(Text.translatable("command.florafare.ignore.inventory.no_food",
                    playerName));
            reportNonFood(source, scan);
            return 0;
        }

        List<String> changed = new ArrayList<>();
        for (Item item : scan.foods()) {
            // "Already in the wanted state" is asked of the resolved answer, not of the
            // config lists: an item excluded by the shipped tag is not in any list this
            // command owns, but adding it again would still change nothing.
            if (FoodExclusions.isExcluded(item) == exclude) continue;

            String id = Registries.ITEM.getId(item).toString();
            FoodExclusions.Entry parsed = FoodExclusions.parse(id);
            if (parsed == null) continue;   // a registry id always parses; belt and braces

            boolean acted = exclude
                    ? addOne(parsed) != AddOutcome.ALREADY
                    : removeOne(parsed) != RemoveOutcome.NOT_LISTED;
            if (acted) changed.add(id);
        }

        if (changed.isEmpty()) {
            source.sendFeedback(() -> Text.translatable(exclude
                            ? "command.florafare.ignore.inventory.none_added"
                            : "command.florafare.ignore.inventory.none_removed", playerName)
                    .formatted(Formatting.GRAY), false);
            reportNonFood(source, scan);
            return 0;
        }

        persistAndResync(source);

        source.sendFeedback(() -> Text.translatable(exclude
                        ? "command.florafare.ignore.inventory.added"
                        : "command.florafare.ignore.inventory.removed",
                changed.size(), playerName).formatted(Formatting.GOLD), true);

        for (int i = 0; i < Math.min(changed.size(), MAX_LISTED); i++) {
            String id = changed.get(i);
            source.sendFeedback(() -> line(id, null), false);
        }
        if (changed.size() > MAX_LISTED) {
            int rest = changed.size() - MAX_LISTED;
            source.sendFeedback(() -> Text.translatable(
                    "command.florafare.ignore.inventory.more", rest).formatted(Formatting.DARK_GRAY),
                    false);
        }
        reportNonFood(source, scan);
        return changed.size();
    }

    private static void reportNonFood(ServerCommandSource source, InventoryScan scan) {
        if (scan.nonFoodStacks() == 0) return;
        // Worth a line: otherwise "I had 40 stacks and it only touched 6" reads as the
        // command having quietly failed on the rest.
        source.sendFeedback(() -> Text.translatable(
                "command.florafare.ignore.inventory.non_food", scan.nonFoodStacks())
                .formatted(Formatting.DARK_GRAY), false);
    }

    /**
     * The distinct edible items a player is carrying, and how many stacks were not food.
     *
     * @param foods         in registry-id order, so the summary reads the same twice
     * @param nonFoodStacks stacks skipped for having no food component
     */
    private record InventoryScan(List<Item> foods, int nonFoodStacks) {}

    private static InventoryScan scanInventory(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();

        // size() spans the main inventory, the armour slots and the off hand, and
        // getStack maps the combined index onto them — so "everything they are carrying"
        // needs no three-way walk of its own.
        Set<Item> foods = new LinkedHashSet<>();
        int nonFood = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            if (!stack.contains(DataComponentTypes.FOOD)) {
                nonFood++;
                continue;
            }
            foods.add(stack.getItem());
        }

        List<Item> ordered = new ArrayList<>(foods);
        ordered.sort((a, b) -> Registries.ITEM.getId(a).compareTo(Registries.ITEM.getId(b)));
        return new InventoryScan(List.copyOf(ordered), nonFood);
    }

    private static void removeAllSpellings(List<String> list, FoodExclusions.Entry target) {
        for (Iterator<String> it = list.iterator(); it.hasNext(); ) {
            FoodExclusions.Entry entry = FoodExclusions.parse(it.next());
            if (entry != null && entry.equals(target)) it.remove();
        }
    }

    /**
     * Writes the file and brings every connected client back into step.
     *
     * <p>Both halves matter. Without the save the rule is gone at the next restart, which
     * is the least expected outcome of a command that reports success; without the
     * re-sync the server stops buffing the item while every client keeps drawing a
     * Florafare tooltip on it and predicting the wrong hunger.
     */
    private static void persistAndResync(ServerCommandSource source) {
        FlorafareConfig.save();
        if (source.getServer() != null) Florafare.resyncExclusions(source.getServer());
    }

    // -------------------------------------------------------------------------
    // SUGGESTIONS
    // -------------------------------------------------------------------------

    /**
     * What is worth excluding: every edible item, and a {@code modid:*} rule for each mod
     * that adds one.
     *
     * <p>Non-food items are left out on purpose — excluding one does nothing, since
     * Florafare never looks at anything that is not edible, and offering them would bury
     * the few hundred entries that mean something under the whole item registry.
     */
    private static CompletableFuture<Suggestions> suggestAdditions(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {

        TreeSet<String> mods = new TreeSet<>();
        List<String> items = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            if (!item.getComponents().contains(DataComponentTypes.FOOD)) continue;
            Identifier id = Registries.ITEM.getId(item);
            items.add(id.toString());
            mods.add(id.getNamespace() + ":*");
        }

        List<String> current = FoodExclusions.canonicalEntries();
        List<String> suggestions = new ArrayList<>(mods.size() + items.size());
        for (String mod : mods)   if (!current.contains(mod))  suggestions.add(mod);
        for (String item : items) if (!current.contains(item)) suggestions.add(item);

        return CommandSource.suggestMatching(suggestions, builder);
    }

    /**
     * What {@code remove} can act on: this config file's own rules, plus every edible
     * item currently being skipped.
     *
     * <p>The second half is the useful one. The shipped defaults live in the
     * {@code #florafare:ignored} tag as individual item ids that appear in no rule list —
     * so without walking the food registry, the one thing a player most wants to type
     * ({@code /florafare ignore remove vinery:mead}) would have no completion at all.
     */
    private static CompletableFuture<Suggestions> suggestRemovals(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {

        List<String> suggestions = new ArrayList<>(
                FoodExclusions.canonicalEntries(
                        FoodExclusions.Effect.EXCLUDE, FoodExclusions.Source.CONFIG));

        TreeSet<String> skippedFood = new TreeSet<>();
        for (Item item : Registries.ITEM) {
            if (!item.getComponents().contains(DataComponentTypes.FOOD)) continue;
            if (!FoodExclusions.isExcluded(item)) continue;
            skippedFood.add(Registries.ITEM.getId(item).toString());
        }
        for (String id : skippedFood) {
            if (!suggestions.contains(id)) suggestions.add(id);
        }

        return CommandSource.suggestMatching(suggestions, builder);
    }
}
