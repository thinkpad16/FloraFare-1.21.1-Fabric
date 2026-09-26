package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodBuffOverrides;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /florafare edit} — changing what a food does, on a running server.
 *
 * <p>Every other way to define a buff is settled before the server is up: a datapack
 * file, or a mod's initializer. Retuning one number therefore meant a text editor, a
 * {@code /reload} that re-reads the whole pack, and — on a server where the balance is
 * being felt out with players actually on it — doing that over and over.
 *
 * <p>This branch edits one field at a time, in place. The change is live on the tick the
 * command runs, on disk before it returns, re-sent to everyone online so their tooltips
 * and journal agree with the server, and re-asserted after the next {@code /reload} and
 * the next restart. See {@link FoodBuffOverrides} for the storage and the undo model.
 *
 * <h2>What a target may be</h2>
 * Everything {@code getConfig} resolves through, addressed the same way a datapack
 * addresses it:
 *
 * <ul>
 *   <li>{@code minecraft:bread} — one item;</li>
 *   <li>{@code tag #c:foods} — every item in an item tag;</li>
 *   <li>{@code namespace:croptopia} — every food a mod adds;</li>
 *   <li>{@code template:default} — every food with nothing more specific.</li>
 * </ul>
 *
 * <p>Tags need the {@code tag} keyword because a leading {@code #} is not a character an
 * identifier argument can read; the rest are ordinary identifiers and are typed directly.
 *
 * <h2>What an edit starts from</h2>
 * Never from zeros. Editing a target that already has an override continues it; a target
 * a datapack defines starts from that definition; and an item with no entry of its own
 * starts from whatever it <em>currently</em> resolves to — its tag's values, its mod's,
 * the template's, or the numbers auto-generated from its vanilla food component.
 *
 * <p>That last case quietly changes the shape of the result, and it is the right change:
 * editing {@code minecraft:beef} when the buff comes from {@code #florafare:meats} writes
 * an entry for beef alone, carrying the tag's other values across, and leaves every other
 * meat on the tag. Storing it under the tag's own name instead would have retuned the
 * whole category from a command that named one item.
 *
 * <h2>Not to be confused with {@code /florafare setbuff}</h2>
 * That one stamps a buff onto the <em>stack in your hand</em> and follows those particular
 * items around. This one changes the rule for an item, a tag or a mod, for everybody.
 */
public final class EditBuffCommand {

    private EditBuffCommand() {}

    /** The three operations {@code PlayerFoodComponent#mapOperation} understands. */
    private static final List<String> VALID_OPERATIONS =
            List.of("add_value", "add_multiplied_base", "add_multiplied_total");

    /**
     * Reads the target out of a parsed command. Two spellings reach the same subtree —
     * a bare identifier and the {@code tag} keyword — so the actions below are built
     * against this rather than against an argument name.
     */
    @FunctionalInterface
    private interface TargetFn {
        String get(CommandContext<ServerCommandSource> context) throws CommandSyntaxException;
    }

    private static final TargetFn PLAIN_TARGET = context ->
            IdentifierArgumentType.getIdentifier(context, "target").toString();

    private static final TargetFn TAG_TARGET = context ->
            "#" + IdentifierArgumentType.getIdentifier(context, "tag");

    // -------------------------------------------------------------------------
    // TREE
    // -------------------------------------------------------------------------

    /** The {@code edit} subtree, for {@code SetBuffCommand} to hang off {@code /florafare}. */
    public static ArgumentBuilder<ServerCommandSource, ?> branch() {
        return CommandManager.literal("edit")
                .executes(EditBuffCommand::executeList)

                // Listed before the identifier argument so Brigadier tries them first. A
                // literal only matches when the whole word matches, so an item genuinely
                // called "list" is still reachable as "minecraft:list".
                .then(CommandManager.literal("list")
                        .executes(EditBuffCommand::executeList))

                .then(CommandManager.literal("reset")
                        .then(CommandManager.literal("all")
                                .executes(EditBuffCommand::executeResetAll)))

                .then(CommandManager.literal("tag")
                        .then(actions(CommandManager.argument("tag", IdentifierArgumentType.identifier())
                                        .suggests(EditBuffCommand::suggestTags),
                                TAG_TARGET)))

                .then(actions(CommandManager.argument("target", IdentifierArgumentType.identifier())
                                .suggests(EditBuffCommand::suggestTargets),
                        PLAIN_TARGET));
    }

    /**
     * Hangs every per-target action off one node.
     *
     * <p>Called once per spelling of the target rather than built once and shared: a
     * Brigadier builder produces a node bound to the parent it is attached to, so the two
     * subtrees have to be constructed separately even though they are identical.
     */
    private static <T extends ArgumentBuilder<ServerCommandSource, T>> T actions(T node,
                                                                                 TargetFn target) {
        return node
                // Bare target: show what it resolves to now. The commonest thing to want
                // before changing anything, and it costs nothing to make it the default.
                .executes(context -> executeShow(context, target))
                .then(CommandManager.literal("show")
                        .executes(context -> executeShow(context, target)))

                .then(CommandManager.literal("duration")
                        .then(CommandManager.argument("ticks",
                                        IntegerArgumentType.integer(1, ActiveFoodBuff.MAX_DURATION_TICKS))
                                .executes(context -> edit(context, target, current -> withDuration(
                                        current, IntegerArgumentType.getInteger(context, "ticks"))))))

                .then(CommandManager.literal("nutrition")
                        .then(CommandManager.argument("points", IntegerArgumentType.integer(0, 20))
                                .executes(context -> edit(context, target, current -> withNutrition(
                                        current, IntegerArgumentType.getInteger(context, "points"))))))

                .then(CommandManager.literal("saturation")
                        .then(CommandManager.argument("modifier", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> edit(context, target, current -> withSaturation(
                                        current,
                                        (float) DoubleArgumentType.getDouble(context, "modifier"))))))

                .then(CommandManager.literal("health")
                        .then(CommandManager.argument("hearts", DoubleArgumentType.doubleArg())
                                .executes(context -> edit(context, target, current -> withHealth(
                                        current, DoubleArgumentType.getDouble(context, "hearts"))))))

                .then(CommandManager.literal("priority")
                        .then(CommandManager.argument("value", IntegerArgumentType.integer())
                                .executes(context -> edit(context, target, current -> withPriority(
                                        current, IntegerArgumentType.getInteger(context, "value"))))))

                .then(CommandManager.literal("always_edible")
                        .then(CommandManager.argument("value", BoolArgumentType.bool())
                                .executes(context -> edit(context, target, current -> withAlwaysEdible(
                                        current, BoolArgumentType.getBool(context, "value"))))))

                .then(CommandManager.literal("effect")
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("effect", IdentifierArgumentType.identifier())
                                        .suggests(EditBuffCommand::suggestEffects)
                                        .then(CommandManager.argument("ticks",
                                                        IntegerArgumentType.integer(1, ActiveFoodBuff.MAX_DURATION_TICKS))
                                                .executes(context -> editEffectAdd(context, target, 0))
                                                .then(CommandManager.argument("amplifier",
                                                                IntegerArgumentType.integer(0, 255))
                                                        .executes(context -> editEffectAdd(context, target,
                                                                IntegerArgumentType.getInteger(context, "amplifier")))))))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("effect", IdentifierArgumentType.identifier())
                                        .suggests((context, builder) ->
                                                suggestCurrentEffects(context, target, builder))
                                        .executes(context -> editEffectRemove(context, target))))
                        .then(CommandManager.literal("clear")
                                .executes(context -> edit(context, target,
                                        current -> withEffects(current, List.of())))))

                .then(CommandManager.literal("attribute")
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("attribute", IdentifierArgumentType.identifier())
                                        .suggests(EditBuffCommand::suggestAttributes)
                                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg())
                                                .executes(context -> editAttributeAdd(context, target, "add_value"))
                                                .then(CommandManager.argument("operation", StringArgumentType.word())
                                                        .suggests((context, builder) ->
                                                                CommandSource.suggestMatching(VALID_OPERATIONS, builder))
                                                        .executes(context -> editAttributeAdd(context, target,
                                                                StringArgumentType.getString(context, "operation")))))))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("attribute", IdentifierArgumentType.identifier())
                                        .suggests((context, builder) ->
                                                suggestCurrentAttributes(context, target, builder))
                                        .executes(context -> editAttributeRemove(context, target))))
                        .then(CommandManager.literal("clear")
                                .executes(context -> edit(context, target,
                                        current -> withAttributes(current, List.of())))))

                .then(CommandManager.literal("reset")
                        .executes(context -> executeReset(context, target)));
    }

    // -------------------------------------------------------------------------
    // THE EDIT ITSELF
    // -------------------------------------------------------------------------

    /**
     * What an edit of this target continues from.
     *
     * <p>See the class javadoc for why the auto-generated and inherited cases are
     * re-targeted rather than stored under the name they resolved through.
     */
    private static FoodBuffData current(String target) {
        FoodBuffData override = FoodBuffOverrides.get(target);
        if (override != null) return override;

        FoodBuffData exact = FoodBuffManager.getConfigByTarget(target);
        if (exact != null) return exact;

        // Only a plain item id can be resolved further — a tag, a namespace or a
        // template has no single item to ask.
        if (!isItemTarget(target)) return FoodBuffData.builder(target).build();

        Identifier id = Identifier.tryParse(target);
        if (id == null || !Registries.ITEM.containsId(id)) {
            return FoodBuffData.builder(target).build();
        }
        FoodBuffData resolved =
                FoodBuffManager.getConfig(Registries.ITEM.get(id).getDefaultStack());
        return resolved == null
                ? FoodBuffData.builder(target).build()
                : retarget(resolved, target);
    }

    private static boolean isItemTarget(String target) {
        return !target.startsWith("#") && !target.startsWith("namespace:")
                && !target.startsWith("template:") && !target.startsWith("potion:");
    }

    /**
     * Writes one edited config through, then tells everyone who needs to know.
     *
     * <p>The order matters. The override goes in first — that is what makes the change
     * real and gets it onto disk — and only then are the clients told, so a player whose
     * tooltip refreshes on the very next frame cannot read a value the server has not
     * committed to yet.
     */
    private static int apply(CommandContext<ServerCommandSource> context,
                             String target, FoodBuffData updated) {
        FoodBuffOverrides.put(target, updated);
        Florafare.syncConfigEntry(context.getSource().getServer(), target, updated);

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.edit.updated", target), true);
        for (Text line : describe(updated)) {
            context.getSource().sendFeedback(() -> line, false);
        }
        return 1;
    }

    /** One-field edits, which all share the read-modify-write above. */
    private static int edit(CommandContext<ServerCommandSource> context, TargetFn targetFn,
                            java.util.function.UnaryOperator<FoodBuffData> change)
            throws CommandSyntaxException {
        String target = FoodBuffManager.normalizeTarget(targetFn.get(context));
        return apply(context, target, change.apply(current(target)));
    }

    private static int editEffectAdd(CommandContext<ServerCommandSource> context,
                                     TargetFn targetFn, int amplifier)
            throws CommandSyntaxException {
        Identifier effectId = IdentifierArgumentType.getIdentifier(context, "effect");
        if (!Registries.STATUS_EFFECT.containsId(effectId)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.edit.unknown_effect", effectId.toString()));
            return 0;
        }
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        String target = FoodBuffManager.normalizeTarget(targetFn.get(context));
        FoodBuffData base = current(target);

        // Replaces rather than appends: two instances of one effect on the same buff
        // means only the stronger is ever visible, and the weaker would then be
        // impossible to address with "effect remove", which takes an id.
        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        for (FoodBuffData.EffectData effect : base.effects()) {
            if (!effect.id().equals(effectId)) effects.add(effect);
        }
        effects.add(new FoodBuffData.EffectData(effectId, ticks, amplifier));
        return apply(context, target, withEffects(base, effects));
    }

    private static int editEffectRemove(CommandContext<ServerCommandSource> context,
                                        TargetFn targetFn) throws CommandSyntaxException {
        Identifier effectId = IdentifierArgumentType.getIdentifier(context, "effect");
        String target = FoodBuffManager.normalizeTarget(targetFn.get(context));
        FoodBuffData base = current(target);

        List<FoodBuffData.EffectData> effects = new ArrayList<>();
        for (FoodBuffData.EffectData effect : base.effects()) {
            if (!effect.id().equals(effectId)) effects.add(effect);
        }
        if (effects.size() == base.effects().size()) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.edit.effect_not_present", effectId.toString(), target));
            return 0;
        }
        return apply(context, target, withEffects(base, effects));
    }

    private static int editAttributeAdd(CommandContext<ServerCommandSource> context,
                                        TargetFn targetFn, String operation)
            throws CommandSyntaxException {
        Identifier attributeId = IdentifierArgumentType.getIdentifier(context, "attribute");
        if (!Registries.ATTRIBUTE.containsId(attributeId)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.edit.unknown_attribute", attributeId.toString()));
            return 0;
        }
        String normalized = operation.toLowerCase(Locale.ROOT);
        if (!VALID_OPERATIONS.contains(normalized)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.edit.unknown_operation",
                    operation, String.join(", ", VALID_OPERATIONS)));
            return 0;
        }

        double amount = DoubleArgumentType.getDouble(context, "amount");
        String target = FoodBuffManager.normalizeTarget(targetFn.get(context));
        FoodBuffData base = current(target);

        // Keyed by attribute alone, not by attribute-and-operation: the modifier ids
        // PlayerFoodComponent builds are keyed that way too, so a second entry for one
        // attribute would silently overwrite the first's modifier at apply time.
        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        for (FoodBuffData.AttributeData attribute : base.attributes()) {
            if (!attribute.attributeId().equals(attributeId)) attributes.add(attribute);
        }
        attributes.add(new FoodBuffData.AttributeData(attributeId, amount, normalized));
        return apply(context, target, withAttributes(base, attributes));
    }

    private static int editAttributeRemove(CommandContext<ServerCommandSource> context,
                                           TargetFn targetFn) throws CommandSyntaxException {
        Identifier attributeId = IdentifierArgumentType.getIdentifier(context, "attribute");
        String target = FoodBuffManager.normalizeTarget(targetFn.get(context));
        FoodBuffData base = current(target);

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>();
        for (FoodBuffData.AttributeData attribute : base.attributes()) {
            if (!attribute.attributeId().equals(attributeId)) attributes.add(attribute);
        }
        if (attributes.size() == base.attributes().size()) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.edit.attribute_not_present", attributeId.toString(), target));
            return 0;
        }
        return apply(context, target, withAttributes(base, attributes));
    }

    // -------------------------------------------------------------------------
    // RESET / SHOW / LIST
    // -------------------------------------------------------------------------

    private static int executeReset(CommandContext<ServerCommandSource> context,
                                    TargetFn targetFn) throws CommandSyntaxException {
        String target = FoodBuffManager.normalizeTarget(targetFn.get(context));
        boolean hadDatapackEntry = FoodBuffOverrides.hasBaseline(target);

        if (!FoodBuffOverrides.reset(target)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.edit.not_overridden", target));
            return 0;
        }
        // Read back rather than remembered: reset either restored a datapack entry or
        // left the target undefined, and the clients have to be told which.
        Florafare.syncConfigEntry(context.getSource().getServer(), target,
                FoodBuffManager.getConfigByTarget(target));

        // Two genuinely different outcomes, and an operator needs to be able to tell
        // them apart: the target either went back to a datapack entry, or stopped being
        // defined at all and now falls through the resolution chain like any other food.
        context.getSource().sendFeedback(() -> Text.translatable(hadDatapackEntry
                ? "command.florafare.edit.reset_to_datapack"
                : "command.florafare.edit.reset_to_nothing", target), true);
        return 1;
    }

    private static int executeResetAll(CommandContext<ServerCommandSource> context) {
        int removed = FoodBuffOverrides.resetAll();
        if (removed == 0) {
            context.getSource().sendError(Text.translatable("command.florafare.edit.none"));
            return 0;
        }
        // The only edit path that sends the whole map: the number of targets it touches
        // is unbounded, and past a handful of them one map beats a packet each.
        Florafare.resyncDatapackConfigs(context.getSource().getServer());
        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.edit.reset_all", removed), true);
        return removed;
    }

    private static int executeShow(CommandContext<ServerCommandSource> context,
                                   TargetFn targetFn) throws CommandSyntaxException {
        String target = FoodBuffManager.normalizeTarget(targetFn.get(context));
        FoodBuffData data = current(target);

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.edit.show_header", target), false);
        context.getSource().sendFeedback(() -> Text.translatable(
                FoodBuffOverrides.get(target) != null
                        ? "command.florafare.edit.source_override"
                        : FoodBuffManager.getConfigByTarget(target) != null
                                ? "command.florafare.edit.source_datapack"
                                : "command.florafare.edit.source_inherited")
                .formatted(Formatting.DARK_GRAY), false);
        for (Text line : describe(data)) {
            context.getSource().sendFeedback(() -> line, false);
        }
        return 1;
    }

    private static int executeList(CommandContext<ServerCommandSource> context) {
        Map<String, FoodBuffData> entries = FoodBuffOverrides.entries();
        if (entries.isEmpty()) {
            context.getSource().sendFeedback(() ->
                    Text.translatable("command.florafare.edit.none"), false);
            return 0;
        }
        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.edit.list_header", entries.size()), false);
        for (Map.Entry<String, FoodBuffData> entry : entries.entrySet()) {
            MutableText line = Text.literal(" • ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal(entry.getKey()).formatted(Formatting.GOLD))
                    .append(Text.literal(" — " + summary(entry.getValue()))
                            .formatted(Formatting.GRAY));
            context.getSource().sendFeedback(() -> line, false);
        }
        return entries.size();
    }

    // -------------------------------------------------------------------------
    // RENDERING
    // -------------------------------------------------------------------------

    /** The one-line form used in {@code list}. */
    private static String summary(FoodBuffData data) {
        StringBuilder text = new StringBuilder();
        text.append(data.duration() / 20).append("s");
        text.append(", ").append(data.nutrition()).append(" nutrition");
        if (data.healthBonus() != 0) text.append(", ").append(data.healthBonus()).append(" health");
        if (!data.effects().isEmpty()) {
            text.append(", ").append(data.effects().size()).append(" effect(s)");
        }
        if (!data.attributes().isEmpty()) {
            text.append(", ").append(data.attributes().size()).append(" attribute(s)");
        }
        return text.toString();
    }

    /** The full form printed after an edit and by {@code show}. */
    private static List<Text> describe(FoodBuffData data) {
        List<Text> lines = new ArrayList<>();
        lines.add(field("duration", data.duration() + " ticks (" + (data.duration() / 20) + "s)"));
        lines.add(field("nutrition", String.valueOf(data.nutrition())));
        lines.add(field("saturation", String.valueOf(data.saturation())));
        lines.add(field("health", String.valueOf(data.healthBonus())));
        lines.add(field("priority", String.valueOf(data.priority())));
        lines.add(field("always_edible", String.valueOf(data.alwaysEdible())));

        for (FoodBuffData.EffectData effect : data.effects()) {
            lines.add(field("effect", effect.id() + " x" + (effect.amplifier() + 1)
                    + " " + (effect.duration() / 20) + "s"));
        }
        for (FoodBuffData.AttributeData attribute : data.attributes()) {
            lines.add(field("attribute", attribute.attributeId()
                    + " " + attribute.amount() + " " + attribute.operation()));
        }
        return lines;
    }

    private static Text field(String name, String value) {
        return Text.literal("   " + name + ": ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(value).formatted(Formatting.WHITE));
    }

    // -------------------------------------------------------------------------
    // SUGGESTIONS
    // -------------------------------------------------------------------------

    /**
     * Everything already defined, plus every food item in the registry.
     *
     * <p>Both halves earn their place: the defined targets are what an operator retunes,
     * and the plain food ids are what they reach for when narrowing a tag's values down
     * to one item — which has no entry of its own yet and so appears in neither the
     * config map nor the override list.
     */
    private static CompletableFuture<Suggestions> suggestTargets(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> targets = new ArrayList<>(FoodBuffOverrides.entries().keySet());
        for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {
            // Tags are typed through the "tag" keyword, so suggesting them here would
            // only offer something the argument cannot read back.
            if (!data.target().startsWith("#")) targets.add(data.target());
        }
        for (net.minecraft.item.Item item : Registries.ITEM) {
            if (!item.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                continue;
            }
            targets.add(Registries.ITEM.getId(item).toString());
        }
        return CommandSource.suggestMatching(dedupe(targets), builder);
    }

    private static CompletableFuture<Suggestions> suggestTags(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> tags = new ArrayList<>();
        for (String target : FoodBuffOverrides.entries().keySet()) {
            if (target.startsWith("#")) tags.add(target.substring(1));
        }
        for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {
            if (data.target().startsWith("#")) tags.add(data.target().substring(1));
        }
        // Every item tag the server knows, so a tag with no entry yet is still reachable.
        Registries.ITEM.streamTags().forEach(tag -> tags.add(tag.id().toString()));
        return CommandSource.suggestMatching(dedupe(tags), builder);
    }

    private static CompletableFuture<Suggestions> suggestEffects(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        return CommandSource.suggestIdentifiers(Registries.STATUS_EFFECT.getIds(), builder);
    }

    private static CompletableFuture<Suggestions> suggestAttributes(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        return CommandSource.suggestIdentifiers(Registries.ATTRIBUTE.getIds(), builder);
    }

    /** Only what this target actually carries, so {@code remove} cannot be typed wrong. */
    private static CompletableFuture<Suggestions> suggestCurrentEffects(
            CommandContext<ServerCommandSource> context, TargetFn targetFn,
            SuggestionsBuilder builder) {
        List<Identifier> ids = new ArrayList<>();
        try {
            for (FoodBuffData.EffectData effect
                    : current(FoodBuffManager.normalizeTarget(targetFn.get(context))).effects()) {
                ids.add(effect.id());
            }
        } catch (Exception ignored) {
            // The target half of the line is still being typed; suggest nothing rather
            // than failing the whole completion.
        }
        return CommandSource.suggestIdentifiers(ids, builder);
    }

    /** @see #suggestCurrentEffects */
    private static CompletableFuture<Suggestions> suggestCurrentAttributes(
            CommandContext<ServerCommandSource> context, TargetFn targetFn,
            SuggestionsBuilder builder) {
        List<Identifier> ids = new ArrayList<>();
        try {
            for (FoodBuffData.AttributeData attribute
                    : current(FoodBuffManager.normalizeTarget(targetFn.get(context))).attributes()) {
                ids.add(attribute.attributeId());
            }
        } catch (Exception ignored) {
            // See above.
        }
        return CommandSource.suggestIdentifiers(ids, builder);
    }

    private static List<String> dedupe(List<String> values) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(values));
    }

    // -------------------------------------------------------------------------
    // FIELD COPIES
    //
    // FoodBuffData is a record with nine components and no wither of its own, and every
    // edit changes exactly one of them. Spelled out here rather than in each caller so
    // the argument order is written once.
    // -------------------------------------------------------------------------

    private static FoodBuffData with(FoodBuffData base, int duration, int nutrition,
                                     float saturation, double healthBonus,
                                     List<FoodBuffData.EffectData> effects,
                                     List<FoodBuffData.AttributeData> attributes,
                                     int priority, boolean alwaysEdible) {
        return new FoodBuffData(base.target(), duration, nutrition, saturation, healthBonus,
                effects, attributes, priority, alwaysEdible);
    }

    private static FoodBuffData withDuration(FoodBuffData base, int duration) {
        return with(base, duration, base.nutrition(), base.saturation(), base.healthBonus(),
                base.effects(), base.attributes(), base.priority(), base.alwaysEdible());
    }

    private static FoodBuffData withNutrition(FoodBuffData base, int nutrition) {
        return with(base, base.duration(), nutrition, base.saturation(), base.healthBonus(),
                base.effects(), base.attributes(), base.priority(), base.alwaysEdible());
    }

    private static FoodBuffData withSaturation(FoodBuffData base, float saturation) {
        return with(base, base.duration(), base.nutrition(), saturation, base.healthBonus(),
                base.effects(), base.attributes(), base.priority(), base.alwaysEdible());
    }

    private static FoodBuffData withHealth(FoodBuffData base, double healthBonus) {
        return with(base, base.duration(), base.nutrition(), base.saturation(), healthBonus,
                base.effects(), base.attributes(), base.priority(), base.alwaysEdible());
    }

    private static FoodBuffData withPriority(FoodBuffData base, int priority) {
        return with(base, base.duration(), base.nutrition(), base.saturation(), base.healthBonus(),
                base.effects(), base.attributes(), priority, base.alwaysEdible());
    }

    private static FoodBuffData withAlwaysEdible(FoodBuffData base, boolean alwaysEdible) {
        return with(base, base.duration(), base.nutrition(), base.saturation(), base.healthBonus(),
                base.effects(), base.attributes(), base.priority(), alwaysEdible);
    }

    private static FoodBuffData withEffects(FoodBuffData base,
                                            List<FoodBuffData.EffectData> effects) {
        return with(base, base.duration(), base.nutrition(), base.saturation(), base.healthBonus(),
                effects, base.attributes(), base.priority(), base.alwaysEdible());
    }

    private static FoodBuffData withAttributes(FoodBuffData base,
                                               List<FoodBuffData.AttributeData> attributes) {
        return with(base, base.duration(), base.nutrition(), base.saturation(), base.healthBonus(),
                base.effects(), attributes, base.priority(), base.alwaysEdible());
    }

    /**
     * The same values under a different target name. See the class javadoc: an item that
     * inherits its buff from a tag must not have the tag's name written into the entry
     * created for it.
     */
    private static FoodBuffData retarget(FoodBuffData base, String target) {
        return new FoodBuffData(target, base.duration(), base.nutrition(), base.saturation(),
                base.healthBonus(), base.effects(), base.attributes(),
                base.priority(), base.alwaysEdible());
    }
}
