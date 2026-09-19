package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.registry.ItemRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Handles the registration and execution of all /florafare administration commands.
 */
public class SetBuffCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {

        dispatcher.register(
                CommandManager.literal("florafare")
                        .requires(source -> source.hasPermissionLevel(
                                net.tend1tnuy.florafare.config.FlorafareConfig.commandPermissionLevel))

                        // Branch: /florafare buffs <player> (Lists what is active right now)
                        .then(CommandManager.literal("buffs")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(SetBuffCommand::executeListBuffs)
                                )
                        )

                        // Branch: /florafare clear <player> (Clears all active buffs and synergies)
                        .then(CommandManager.literal("clear")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(SetBuffCommand::executeClearBuffs)
                                )
                        )

                        // Branch: /florafare setbuff (Dynamic buff on item in hand)
                        .then(CommandManager.literal("setbuff")
                                .then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("nutrition", IntegerArgumentType.integer(0))
                                                .then(CommandManager.argument("saturation", DoubleArgumentType.doubleArg(0.0))
                                                        .then(CommandManager.argument("health", DoubleArgumentType.doubleArg(0.0))
                                                                .executes(context -> executeSetBuff(context, null, 0.0, null))
                                                                .then(CommandManager.argument("attr_id", IdentifierArgumentType.identifier())
                                                                        // Both arguments used to be free text, and a value neither
                                                                        // registry nor operation recognised was accepted in silence:
                                                                        // the buff was stamped onto the stack and then applied nothing,
                                                                        // or quietly fell back to add_value. Suggested here and
                                                                        // rejected in executeSetBuff.
                                                                        .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(
                                                                                Registries.ATTRIBUTE.getIds(), builder))
                                                                        .then(CommandManager.argument("attr_amount", DoubleArgumentType.doubleArg())
                                                                                .then(CommandManager.argument("attr_op", StringArgumentType.word())
                                                                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                                                                                VALID_OPERATIONS, builder))
                                                                                        .executes(context -> executeSetBuff(
                                                                                                context,
                                                                                                IdentifierArgumentType.getIdentifier(context, "attr_id"),
                                                                                                DoubleArgumentType.getDouble(context, "attr_amount"),
                                                                                                StringArgumentType.getString(context, "attr_op")
                                                                                        ))
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )

                        // Branch: /florafare buff give|remove
                        // One "buff" node with both children. Registering the literal
                        // twice happened to work — Brigadier merges same-named literals
                        // when it adds them — but it reads as two separate commands and
                        // relied on that merge to not be two separate commands.
                        .then(CommandManager.literal("buff")
                                // Grant a buff from a datapack config to a player.
                                .then(CommandManager.literal("give")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                // Greedy, like the journal arguments below, so a
                                                // "mod:item" id can be typed — and click-completed —
                                                // without quotes. Quoted input still works, see unquote().
                                                .then(CommandManager.argument("targetId", StringArgumentType.greedyString())
                                                        .suggests((ctx, builder) ->
                                                                suggestTargets(configuredTargets(), builder))
                                                        .executes(SetBuffCommand::executeBuffGive)
                                                )
                                        )
                                )
                                // Drop one named buff.
                                .then(CommandManager.literal("remove")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .then(CommandManager.argument("targetId", StringArgumentType.greedyString())
                                                        // Only what that player is actually carrying can be
                                                        // removed, so that is the list worth offering.
                                                        .suggests((ctx, builder) ->
                                                                suggestTargets(activeBuffTargets(ctx), builder))
                                                        .executes(SetBuffCommand::executeBuffRemove)
                                                )
                                        )
                                )
                        )

                        // Branch: /florafare journal … (Journal item, and the discovery
                        // list behind it: what the player has "tasted" and can read.)
                        //
                        // Discovery is normally only ever written by eating, which makes
                        // testing anything downstream of it — a journal page, a tooltip
                        // that unlocks, a synergy hint — a matter of finding and eating
                        // the right item. These put the same list under direct control.
                        .then(CommandManager.literal("journal")
                                .then(CommandManager.literal("give")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .executes(SetBuffCommand::executeJournalGive)
                                        )
                                )

                                // /florafare journal list <player> [filter]
                                .then(CommandManager.literal("list")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .executes(context -> executeJournalList(context, null))
                                                .then(CommandManager.argument("filter", StringArgumentType.greedyString())
                                                        .executes(context -> executeJournalList(context,
                                                                unquote(StringArgumentType.getString(context, "filter"))))
                                                )
                                        )
                                )

                                // /florafare journal unlock <player> all|<food>
                                //
                                // "all" is a literal child, and Brigadier matches literals
                                // ahead of arguments, so it wins over the string argument
                                // below without either having to know about the other.
                                .then(CommandManager.literal("unlock")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .then(CommandManager.literal("all")
                                                        .executes(SetBuffCommand::executeJournalUnlockAll)
                                                )
                                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                                        .suggests((ctx, builder) ->
                                                                suggestTargets(journalTargets(), builder))
                                                        .executes(SetBuffCommand::executeJournalUnlock)
                                                )
                                        )
                                )

                                // /florafare journal lock <player> all|<food>
                                .then(CommandManager.literal("lock")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .then(CommandManager.literal("all")
                                                        .executes(SetBuffCommand::executeJournalLockAll)
                                                )
                                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                                        // Suggested from what this player actually knows — the
                                                        // only list that can be locked, and the short one.
                                                        .suggests((ctx, builder) -> suggestTargets(
                                                                discoveredOf(ctx, PlayerFoodComponent::getDiscoveredFoods),
                                                                builder))
                                                        .executes(SetBuffCommand::executeJournalLock)
                                                )
                                        )
                                )

                                // /florafare journal synergy unlock|lock <player> all|<synergy>
                                .then(CommandManager.literal("synergy")
                                        .then(CommandManager.literal("unlock")
                                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                                        .then(CommandManager.literal("all")
                                                                .executes(SetBuffCommand::executeSynergyUnlockAll)
                                                        )
                                                        .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                                                .suggests((ctx, builder) ->
                                                                        suggestTargets(synergyIds(), builder))
                                                                .executes(SetBuffCommand::executeSynergyUnlock)
                                                        )
                                                )
                                        )
                                        .then(CommandManager.literal("lock")
                                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                                        .then(CommandManager.literal("all")
                                                                .executes(SetBuffCommand::executeSynergyLockAll)
                                                        )
                                                        .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                                                .suggests((ctx, builder) -> suggestTargets(
                                                                        discoveredOf(ctx, PlayerFoodComponent::getDiscoveredSynergies),
                                                                        builder))
                                                                .executes(SetBuffCommand::executeSynergyLock)
                                                        )
                                                )
                                        )
                                )
                        )

                        // Branch: /florafare repair <player> (Strips orphaned Florafare attribute modifiers)
                        .then(CommandManager.literal("repair")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(SetBuffCommand::executeRepair)
                                )
                        )

                        // Branch: /florafare validate (Diagnoses loaded food_buffs/food_synergies configs)
                        .then(CommandManager.literal("validate")
                                .executes(SetBuffCommand::executeValidate)
                        )

                        // Branch: /florafare explain [item] (Why does THIS item have THIS buff?)
                        //
                        // /florafare validate answers "is anything wrong with the pack";
                        // this answers "why did my entry do nothing", which is a different
                        // question and the one that costs pack authors their afternoon. With
                        // no argument it reads the stack in hand, components and all, so a
                        // command-stamped or renamed stack can be interrogated directly.
                        .then(CommandManager.literal("explain")
                                .executes(SetBuffCommand::executeExplainHeld)
                                .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                                        .executes(SetBuffCommand::executeExplain)
                                )
                        )
        );
    }

    /**
     * Drops a single active buff, addressed by its config target or by the item that
     * was eaten. Forgotten Mead only removes the newest, so this is the only way to
     * clear one buff out of the middle of a full set.
     */
    private static int executeBuffRemove(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        String rawTarget = unquote(StringArgumentType.getString(context, "targetId"));
        String target = FoodBuffManager.normalizeTarget(rawTarget);

        PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();
        boolean removed = component.removeBuff(target);

        if (!removed) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.buff_remove.not_active", target, player.getName().getString()));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.buff_remove.success", target, player.getName().getString()), true);
        return 1;
    }

    /** The three operations {@code PlayerFoodComponent#mapOperation} understands. */
    private static final List<String> VALID_OPERATIONS =
            List.of("add_value", "add_multiplied_base", "add_multiplied_total");

    /**
     * Prints the buffs and synergies a player is carrying, with what each one is actually
     * applying and how long it has left.
     *
     * <p>Until this existed the only way an admin could act on a player's buff state was
     * {@code /florafare clear}, which destroys the very thing they were trying to look
     * at. The attribute modifiers are read back off the buff itself rather than off the
     * config it came from, so a buff that has drifted out of step with its config — the
     * exact situation {@code /florafare repair} exists for — shows what is really on the
     * player, not what should have been.
     */
    private static int executeListBuffs(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        ServerCommandSource source = context.getSource();
        PlayerFoodComponent component =
                ((IFoodComponentProvider) player).florafare$getFoodComponent();

        // Snapshots: sendFeedback takes a supplier, which Brigadier may call later, and
        // the component's own lists are mutated by the player tick as buffs expire.
        List<ActiveFoodBuff> buffs     = List.copyOf(component.getActiveBuffs());
        List<ActiveFoodBuff> synergies = List.copyOf(component.getActiveSynergies());
        String playerName = player.getName().getString();

        if (buffs.isEmpty() && synergies.isEmpty()) {
            source.sendFeedback(() -> Text.translatable(
                    "command.florafare.buffs.none", playerName), false);
            return 0;
        }

        source.sendFeedback(() -> Text.translatable("command.florafare.buffs.header",
                playerName, buffs.size(), PlayerFoodComponent.getMaxBuffSlots()), false);
        for (ActiveFoodBuff buff : buffs) {
            source.sendFeedback(() -> describeBuff(buff, false), false);
        }

        if (!synergies.isEmpty()) {
            source.sendFeedback(() -> Text.translatable(
                    "command.florafare.buffs.synergies", synergies.size()), false);
            for (ActiveFoodBuff synergy : synergies) {
                source.sendFeedback(() -> describeBuff(synergy, true), false);
            }
        }
        return buffs.size() + synergies.size();
    }

    /** One line: what the buff is, how long it has left, and what it is applying. */
    private static Text describeBuff(ActiveFoodBuff buff, boolean isSynergy) {
        MutableText line = Text.literal(" \u2022 ").formatted(Formatting.DARK_GRAY);
        line.append(Text.literal(buff.getTarget())
                .formatted(isSynergy ? Formatting.GOLD : Formatting.WHITE));

        // Only when it adds something: for a plain item-id target the two are identical,
        // and for a synergy the "consumed item" is just whichever icon the HUD picked.
        if (!isSynergy && !buff.getTarget().equals(buff.getConsumedItemId())) {
            line.append(Text.literal(" (" + buff.getConsumedItemId() + ")")
                    .formatted(Formatting.GRAY));
        }

        line.append(Text.literal(" \u2014 " + ticksToMmss(buff.getDurationRemaining())
                + " / " + ticksToMmss(buff.getInitialDuration())).formatted(Formatting.AQUA));

        String modifiers = describeModifiers(buff);
        if (!modifiers.isEmpty()) {
            line.append(Text.literal(" \u00b7 " + modifiers).formatted(Formatting.BLUE));
        }
        return line;
    }

    private static String describeModifiers(ActiveFoodBuff buff) {
        StringBuilder sb = new StringBuilder();
        for (ActiveFoodBuff.AppliedModifier modifier : buff.getAppliedModifiers().values()) {
            if (sb.length() > 0) sb.append(", ");
            if (modifier.amount() > 0) sb.append('+');
            sb.append(trim(modifier.amount()))
              .append(' ')
              .append(modifier.attributeId().getPath());
            if (!"add_value".equals(modifier.operation())) {
                sb.append(" (").append(modifier.operation()).append(')');
            }
        }
        return sb.toString();
    }

    private static String trim(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 1000.0) / 1000.0);
    }

    /**
     * {@code mm:ss} for a tick count. Deliberately duplicated from
     * {@code BuffDescription#mmss} instead of reused: that class reads the local player's
     * discovery set through {@code MinecraftClient} and must never be loaded on a
     * dedicated server, which is exactly where this command runs.
     */
    private static String ticksToMmss(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static int executeJournalGive(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");

        ItemStack journal = new ItemStack(ItemRegistry.FOOD_JOURNAL);
        if (!player.getInventory().insertStack(journal)) {
            player.dropItem(journal, false);
        }

        PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();
        component.setHasReceivedJournal(true);

        context.getSource().sendFeedback(() -> Text.translatable("command.florafare.journal_give.success", player.getName().getString()), true);
        return 1;
    }

    // -------------------------------------------------------------------------
    // JOURNAL DISCOVERY
    //
    // The journal's unlocked state is the player's discovery set, and the only thing
    // that normally writes to it is eating. Everything below edits it directly, so a
    // journal page, an unlocking tooltip or a synergy hint can be put into any state
    // without hunting down and eating the item that would have produced it.
    // -------------------------------------------------------------------------

    /** How many discovered ids one {@code journal list} prints before it stops. */
    private static final int LIST_LIMIT = 30;

    /**
     * Completes an id argument the way vanilla completes one.
     *
     * <p>{@code CommandSource.suggestMatching} only ever matches from the front of the
     * whole string, so with nothing typed it offers every candidate but the moment you
     * type "bacon" it offers nothing at all — every id starts with its namespace. Vanilla
     * does not behave that way: {@code /give @s bacon} finds {@code farmersdelight:bacon},
     * because {@link CommandSource#forEachMatching} matches the path as well as the
     * namespace, and offers bare "namespace:" entries to narrow with. That is what this
     * runs the identifier-shaped candidates through.
     *
     * <p>Anything that is not a plain {@code namespace:path} — a {@code #tag}, a
     * {@code potion:} target, {@code template:default}, an id left behind by a mod that
     * has since been removed — cannot go through that matcher, so it keeps the simple
     * prefix match.
     */
    private static CompletableFuture<Suggestions> suggestTargets(Iterable<String> targets,
                                                                 SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        List<Identifier> ids = new ArrayList<>();

        for (String target : targets) {
            // Tested for a ":" first, and against the round trip: Identifier.tryParse
            // would just as happily turn a bare "hearty_lunch" into "minecraft:hearty_lunch"
            // and suggest an id that matches nothing.
            Identifier id = target.indexOf(':') >= 0 ? Identifier.tryParse(target) : null;
            if (id != null && id.toString().equals(target)) {
                ids.add(id);
            } else if (CommandSource.shouldSuggest(remaining, target.toLowerCase(Locale.ROOT))) {
                builder.suggest(target);
            }
        }

        CommandSource.forEachMatching(ids, remaining, id -> id, id -> builder.suggest(id.toString()));
        return builder.buildFuture();
    }

    // -------------------------------------------------------------------------
    // SUGGESTION CACHES
    //
    // Brigadier asks a suggestion provider for its candidates on every keystroke the
    // player types, and journalTargets() answers by walking the entire item registry and
    // resolving a config for each item — tens of thousands of lookups per character on a
    // modpack, off the main thread, while the player is mid-word. The lists only change
    // when the configs do, so they are built once per config generation and shared.
    //
    // Held in an AtomicReference rather than plain fields because command completion is
    // served from the network thread: two players typing at once would otherwise be able
    // to see a half-built list.
    // -------------------------------------------------------------------------

    /** A cached suggestion list, tagged with the config generation it was built from. */
    private record CachedTargets(int generation, List<String> targets) {}

    private static final AtomicReference<CachedTargets> CONFIGURED_TARGETS_CACHE =
            new AtomicReference<>();
    private static final AtomicReference<CachedTargets> JOURNAL_TARGETS_CACHE =
            new AtomicReference<>();

    private static List<String> cached(AtomicReference<CachedTargets> cache,
                                       Supplier<List<String>> build) {
        int generation = FoodBuffManager.configGeneration();
        CachedTargets current = cache.get();
        if (current != null && current.generation() == generation) return current.targets();

        List<String> built = List.copyOf(build.get());
        // Plain set, no compareAndSet: two threads racing here build the same list from
        // the same generation, so whichever lands second is writing the same answer.
        cache.set(new CachedTargets(generation, built));
        return built;
    }

    /** Every target a datapack (or the API) actually defined — what {@code buff give} takes. */
    private static List<String> configuredTargets() {
        return cached(CONFIGURED_TARGETS_CACHE, () -> {
            List<String> targets = new ArrayList<>();
            for (FoodBuffData data : FoodBuffManager.getAllConfigs()) targets.add(data.target());
            return targets;
        });
    }

    /**
     * What {@code buff remove} can address on this player: each running buff's config
     * target, plus the item it came from, since the command accepts either.
     */
    private static Collection<String> activeBuffTargets(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
            Set<String> targets = new java.util.LinkedHashSet<>();
            for (ActiveFoodBuff buff : componentOf(player).getActiveBuffs()) {
                targets.add(buff.getTarget());
                targets.add(buff.getConsumedItemId());
            }
            return targets;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Every id the journal has a row for: one per edible item Florafare manages, plus
     * the {@code potion:} targets, which are rows without an item of their own.
     *
     * <p>Deliberately the same set {@code FoodJournalScreen#rebuildCache} builds — an
     * id outside it can be put in the discovery set, but nothing would ever display it.
     */
    private static List<String> journalTargets() {
        return cached(JOURNAL_TARGETS_CACHE, () -> {
            List<String> targets = new ArrayList<>();
            for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {
                if (data.target().startsWith("potion:")) targets.add(data.target());
            }
            for (Item item : Registries.ITEM) {
                // Cheap test first: the registry is tens of thousands of items and only a
                // few hundred are food, and getConfig needs a stack allocated per item.
                if (!item.getComponents().contains(DataComponentTypes.FOOD)) continue;
                if (FoodBuffManager.getConfig(item.getDefaultStack()) == null) continue;
                targets.add(Registries.ITEM.getId(item).toString());
            }
            return targets;
        });
    }

    /** Whether the journal would have a row for this id — see {@link #journalTargets}. */
    private static boolean isJournalTarget(String target) {
        if (target.startsWith("potion:")) {
            return FoodBuffManager.getConfigByTarget(target) != null;
        }
        Identifier id = Identifier.tryParse(target);
        if (id == null || !Registries.ITEM.containsId(id)) return false;
        return FoodBuffManager.getConfig(Registries.ITEM.get(id).getDefaultStack()) != null;
    }

    private static List<String> synergyIds() {
        List<String> ids = new ArrayList<>();
        for (FoodSynergyData synergy : FoodSynergyManager.getAllSynergies()) ids.add(synergy.id());
        return ids;
    }

    /**
     * The half of a player's discovery set a {@code lock} suggestion should offer.
     * Locking only ever makes sense for something already discovered, and that list is
     * short — far better to suggest than the whole food registry.
     *
     * <p>Answers empty when the player argument does not resolve: this runs while the
     * command line is still half-typed, where an unmatched selector is normal rather
     * than an error worth surfacing.
     */
    private static Collection<String> discoveredOf(
            CommandContext<ServerCommandSource> context,
            java.util.function.Function<PlayerFoodComponent, Set<String>> which) {
        try {
            ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
            return new ArrayList<>(
                    which.apply(((IFoodComponentProvider) player).florafare$getFoodComponent()));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Trims, and drops one surrounding pair of quotes.
     *
     * <p>These arguments are greedy strings, because Brigadier's quotable string stops
     * at the ":" in "mod:item" and the whole point of these commands is to type an item
     * id quickly. A greedy string takes the quotes literally if someone types them out
     * of habit from the neighbouring {@code /florafare buff} commands, which do want
     * them — so both spellings are accepted here rather than one of them failing with
     * "no journal entry for '\"mod:item\"'".
     */
    private static String unquote(String raw) {
        String value = raw.trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                 || (value.startsWith("'")  && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static PlayerFoodComponent componentOf(ServerPlayerEntity player) {
        return ((IFoodComponentProvider) player).florafare$getFoodComponent();
    }

    private static int executeJournalUnlock(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        String target = FoodBuffManager.normalizeTarget(
                unquote(StringArgumentType.getString(context, "target")));

        // Rejected rather than stored: a typo would otherwise sit in the discovery set
        // forever, counting towards nothing and displayed by nothing.
        if (!isJournalTarget(target)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.journal_unlock.unknown", target));
            return 0;
        }

        if (!componentOf(player).unlockFood(target)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.journal_unlock.already", target, player.getName().getString()));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.journal_unlock.success", target, player.getName().getString()), true);
        return 1;
    }

    private static int executeJournalUnlockAll(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        int added = componentOf(player).unlockFoods(journalTargets());

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.journal_unlock_all.success", added, player.getName().getString()), true);
        return added;
    }

    private static int executeJournalLock(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        // Not validated against the registry, unlike unlock: an id left behind by a mod
        // that has since been removed is exactly the kind of thing this has to be able
        // to clear out.
        String target = FoodBuffManager.normalizeTarget(
                unquote(StringArgumentType.getString(context, "target")));

        if (!componentOf(player).lockFood(target)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.journal_lock.not_discovered", target, player.getName().getString()));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.journal_lock.success", target, player.getName().getString()), true);
        return 1;
    }

    private static int executeJournalLockAll(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        int removed = componentOf(player).lockAllFoods();

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.journal_lock_all.success", removed, player.getName().getString()), true);
        return removed;
    }

    private static int executeSynergyUnlock(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        String target = unquote(StringArgumentType.getString(context, "target"));

        if (FoodSynergyManager.getSynergy(target) == null) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.synergy_unlock.unknown", target));
            return 0;
        }

        if (!componentOf(player).unlockSynergy(target)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.synergy_unlock.already", target, player.getName().getString()));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.synergy_unlock.success", target, player.getName().getString()), true);
        return 1;
    }

    private static int executeSynergyUnlockAll(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        int added = componentOf(player).unlockSynergies(synergyIds());

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.synergy_unlock_all.success", added, player.getName().getString()), true);
        return added;
    }

    private static int executeSynergyLock(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        String target = unquote(StringArgumentType.getString(context, "target"));

        if (!componentOf(player).lockSynergy(target)) {
            context.getSource().sendError(Text.translatable(
                    "command.florafare.synergy_lock.not_discovered", target, player.getName().getString()));
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.synergy_lock.success", target, player.getName().getString()), true);
        return 1;
    }

    private static int executeSynergyLockAll(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        int removed = componentOf(player).lockAllSynergies();

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.synergy_lock_all.success", removed, player.getName().getString()), true);
        return removed;
    }

    /**
     * What the player has discovered, against what there is to discover.
     *
     * <p>The id list is capped at {@value #LIST_LIMIT} lines — a modpack's discovery set
     * runs into the hundreds, and a command that scrolls the chat buffer away is no use
     * for reading the counts at the top of it. The optional filter is a plain substring
     * match, which is enough to answer "did that one food register".
     */
    private static int executeJournalList(CommandContext<ServerCommandSource> context, String filter)
            throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        ServerCommandSource source = context.getSource();
        PlayerFoodComponent component = componentOf(player);

        List<String> foods = new ArrayList<>(component.getDiscoveredFoods());
        List<String> synergies = new ArrayList<>(component.getDiscoveredSynergies());
        int totalFoods = journalTargets().size();
        int totalSynergies = FoodSynergyManager.getAllSynergies().size();

        source.sendFeedback(() -> Text.translatable("command.florafare.journal_list.header",
                player.getName().getString(), foods.size(), totalFoods,
                synergies.size(), totalSynergies), false);

        List<String> shown = new ArrayList<>();
        for (String id : foods) {
            if (filter == null || id.contains(filter)) shown.add(id);
        }
        for (String id : synergies) {
            if (filter == null || id.contains(filter)) shown.add("synergy: " + id);
        }
        Collections.sort(shown);

        if (shown.isEmpty()) {
            source.sendFeedback(() -> Text.translatable(
                    "command.florafare.journal_list.none", player.getName().getString()), false);
            return 0;
        }

        for (String id : shown.subList(0, Math.min(LIST_LIMIT, shown.size()))) {
            source.sendFeedback(() -> Text.literal(" • " + id).formatted(Formatting.GRAY), false);
        }
        if (shown.size() > LIST_LIMIT) {
            int more = shown.size() - LIST_LIMIT;
            source.sendFeedback(() -> Text.translatable(
                    "command.florafare.journal_list.more", more), false);
        }
        return shown.size();
    }

    private static int executeClearBuffs(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
        PlayerFoodComponent component = ((IFoodComponentProvider) targetPlayer).florafare$getFoodComponent();

        component.clearAllBuffs();

        context.getSource().sendFeedback(() -> Text.translatable("command.florafare.clear.success", targetPlayer.getName().getString()), true);
        return 1;
    }

    private static int executeSetBuff(CommandContext<ServerCommandSource> context, Identifier attrId, Double amount, String op) {
        ServerCommandSource source = context.getSource();
        if (source.getPlayer() == null) return 0;

        ItemStack stack = source.getPlayer().getMainHandStack();

        if (stack.isEmpty()) {
            source.sendError(Text.translatable("command.florafare.error.empty_hand"));
            return 0;
        }

        // Checked before anything is written. An unregistered attribute is skipped
        // without a word by PlayerFoodComponent#applyBuffEffects, and an unrecognised
        // operation silently becomes add_value — so the command reported success and
        // stamped a UUID onto the stack for a buff that would never do what was asked.
        if (attrId != null) {
            if (!Registries.ATTRIBUTE.containsId(attrId)) {
                source.sendError(Text.translatable(
                        "command.florafare.error.unknown_attribute", attrId.toString()));
                return 0;
            }
            String normalizedOp = op == null ? "" : op.toLowerCase(Locale.ROOT);
            if (!VALID_OPERATIONS.contains(normalizedOp)) {
                source.sendError(Text.translatable("command.florafare.error.unknown_operation",
                        String.valueOf(op), String.join(", ", VALID_OPERATIONS)));
                return 0;
            }
            op = normalizedOp;
        }

        String uniqueId = UUID.randomUUID().toString();

        NbtCompound customData = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        customData.putString("FlorafareBuffId", uniqueId);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData));

        List<FoodBuffData.AttributeData> attrs = new ArrayList<>();
        if (attrId != null) {
            attrs.add(new FoodBuffData.AttributeData(attrId, amount, op));
        }

        FoodBuffData data = new FoodBuffData(
                "stack:" + uniqueId,
                IntegerArgumentType.getInteger(context, "duration"),
                IntegerArgumentType.getInteger(context, "nutrition"),
                (float) DoubleArgumentType.getDouble(context, "saturation"),
                DoubleArgumentType.getDouble(context, "health"),
                new ArrayList<>(),
                attrs,
                0,
                false
        );

        FoodBuffManager.setRuntimeStackConfig(uniqueId, data);
        FoodBuffManager.saveRuntimeConfigs(source.getServer());

        source.sendFeedback(() -> Text.translatable("command.florafare.setbuff.success"), true);
        return 1;
    }

    private static int executeBuffGive(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        // Normalize (trim, default namespace) so the input matches the registered config keys.
        String targetId = FoodBuffManager.normalizeTarget(
                unquote(StringArgumentType.getString(context, "targetId")));

        FoodBuffData data = FoodBuffManager.getConfigByTarget(targetId);

        if (data != null) {
            PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();

            // Resolve a display stack for plain item targets so the HUD shows the right icon.
            ItemStack displayStack = Items.APPLE.getDefaultStack();
            Identifier itemId = Identifier.tryParse(targetId);
            boolean isConcreteItem = itemId != null && Registries.ITEM.containsId(itemId);
            if (isConcreteItem) {
                displayStack = Registries.ITEM.get(itemId).getDefaultStack();
            }

            // Only a concrete item has a journal entry. A "#tag", "namespace:" or
            // "template:" target has none, and unlocking it left an id in the discovery
            // set that no journal row and no toast could ever resolve — the toast fell
            // back to showing an apple. Unlocked before the slot check, matching what
            // eating does: the food still counts as tasted even with no room for a buff.
            if (isConcreteItem) {
                component.unlockFood(itemId.toString());
            }

            if (!component.tryAddBuff(displayStack, data)) {
                context.getSource().sendError(Text.translatable("command.florafare.error.slots_full", player.getName().getString()));
                return 0;
            }

            context.getSource().sendFeedback(() -> Text.translatable("command.florafare.buff_give.success", targetId, player.getName().getString()), true);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.florafare.error.buff_not_found", targetId));
            return 0;
        }
    }

    /**
     * Scans every registered attribute (vanilla and modded) for modifiers Florafare
     * applied, and removes them regardless of the mod's current in-memory buff state.
     * Recovers a player whose stats got stuck from a corrupted save, a version
     * migration, or Florafare being removed and reinstalled.
     */
    private static int executeRepair(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");

        // Same scan the component runs on every load; kept as a command because a
        // player can also get stuck without relogging (a crash mid-buff, say).
        int removed = ((IFoodComponentProvider) player).florafare$getFoodComponent()
                .stripFlorafareModifiers();

        context.getSource().sendFeedback(() -> Text.translatable(
                "command.florafare.repair.success", removed, player.getName().getString()), true);
        return removed;
    }

    /**
     * Diagnoses the currently loaded food_buffs/food_synergies configs for problems
     * that don't surface as load-time errors: synergies that can never activate
     * given {@code maxBuffSlots}, requirements that can never be satisfied (item not
     * edible, excluded, or tag empty), and datapack targets left ambiguous by a
     * same-priority tie.
     */
    private static int executeValidate(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        List<String> issues = new ArrayList<>();
        int maxSlots = PlayerFoodComponent.getMaxBuffSlots();

        for (FoodSynergyData synergy : FoodSynergyManager.getAllSynergies()) {
            if (synergy.requirements().size() > maxSlots) {
                issues.add(String.format(
                        "synergy '%s' needs %d buffs at once, but maxBuffSlots is %d — it can never activate.",
                        synergy.id(), synergy.requirements().size(), maxSlots));
            }

            for (String req : synergy.requirements()) {
                if (req.startsWith("#")) {
                    Identifier tagId = Identifier.tryParse(req.substring(1));
                    if (tagId == null) {
                        issues.add(String.format(
                                "synergy '%s' requirement '%s' is not a valid tag id.", synergy.id(), req));
                        continue;
                    }
                    boolean anyItems = false;
                    boolean anyReachable = false;
                    for (RegistryEntry<Item> entry
                            : Registries.ITEM.iterateEntries(TagKey.of(RegistryKeys.ITEM, tagId))) {
                        anyItems = true;
                        if (FoodBuffManager.getConfig(entry.value().getDefaultStack()) != null) {
                            anyReachable = true;
                            break;
                        }
                    }
                    if (!anyItems) {
                        issues.add(String.format(
                                "synergy '%s' requirement '%s' matches no loaded items — it can never be satisfied.",
                                synergy.id(), req));
                    } else if (!anyReachable) {
                        issues.add(String.format(
                                "synergy '%s' requirement '%s' matches items, but none of them can grant a buff "
                                        + "(all excluded or non-edible) — it can never be satisfied.",
                                synergy.id(), req));
                    }
                } else {
                    Identifier itemId = Identifier.tryParse(req);
                    if (itemId == null || !Registries.ITEM.containsId(itemId)) {
                        issues.add(String.format(
                                "synergy '%s' requirement '%s' is not a valid/registered item id.",
                                synergy.id(), req));
                    } else if (FoodBuffManager.getConfig(Registries.ITEM.get(itemId).getDefaultStack()) == null) {
                        issues.add(String.format(
                                "synergy '%s' requirement '%s' can never grant a buff (not edible, or excluded) "
                                        + "— it can never be satisfied.",
                                synergy.id(), req));
                    }
                }
            }
        }

        // Sizing problems belong here too: they are invisible in-game until someone
        // notices every tooltip is wrong.
        issues.addAll(Florafare.syncPayloadIssues());

        for (String target : FoodBuffManager.getAmbiguousTargets()) {
            issues.add(String.format(
                    "target '%s' is defined by multiple entries at the same priority — which one wins is not guaranteed.",
                    target));
        }

        if (issues.isEmpty()) {
            source.sendFeedback(() -> Text.literal(String.format(
                    "§aFlorafare validate: no issues found across %d food configs and %d synergies.",
                    FoodBuffManager.getConfigCount(), FoodSynergyManager.getAllSynergies().size())), false);
        } else {
            source.sendFeedback(() -> Text.literal(
                    "§eFlorafare validate found " + issues.size() + " issue(s) — see server log for details:"), false);
            for (String issue : issues) {
                Florafare.LOGGER.warn("[Florafare Validate] {}", issue);
                source.sendFeedback(() -> Text.literal(" - " + issue), false);
            }
        }
        return issues.size();
    }

    // -------------------------------------------------------------------------
    // EXPLAIN
    // -------------------------------------------------------------------------

    private static int executeExplainHeld(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) {
            context.getSource().sendError(Text.translatable("command.florafare.explain.empty_hand"));
            return 0;
        }
        return explain(context.getSource(), held);
    }

    private static int executeExplain(CommandContext<ServerCommandSource> context)
            throws CommandSyntaxException {
        // count 1, allowOversizedStacks false — this stack is never given to anyone, it
        // only carries the item and whatever components were typed with it.
        ItemStack stack = ItemStackArgumentType.getItemStackArgument(context, "item")
                .createStack(1, false);
        return explain(context.getSource(), stack);
    }

    /**
     * Prints the resolution chain for one stack: what Florafare decided, which rung of
     * the ladder decided it, every entry that could have applied, and the values the
     * winner actually produces.
     */
    private static int explain(ServerCommandSource source, ItemStack stack) {
        FoodBuffManager.Resolution resolution = FoodBuffManager.explain(stack);
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

        source.sendFeedback(() -> Text.translatable("command.florafare.explain.header", itemId), false);

        // The four dead ends: nothing further to say, and saying it plainly is the point.
        switch (resolution.source()) {
            case MEAD -> {
                source.sendFeedback(() -> Text.translatable("command.florafare.explain.mead"), false);
                return 0;
            }
            case EXCLUDED_BY_CONFIG -> {
                source.sendFeedback(() -> Text.translatable(
                        "command.florafare.explain.excluded_config"), false);
                return 0;
            }
            case EXCLUDED_BY_TAG -> {
                source.sendFeedback(() -> Text.translatable(
                        "command.florafare.explain.excluded_tag", resolution.target()), false);
                return 0;
            }
            case NOT_FOOD -> {
                source.sendFeedback(() -> Text.translatable("command.florafare.explain.not_food"), false);
                return 0;
            }
            default -> { }
        }

        String sourceKey = "command.florafare.explain.source." +
                resolution.source().name().toLowerCase(Locale.ROOT);
        source.sendFeedback(() -> Text.translatable("command.florafare.explain.chosen",
                        Text.literal(String.valueOf(resolution.target())).formatted(Formatting.GREEN),
                        Text.translatable(sourceKey).formatted(Formatting.GRAY)), false);

        if (!resolution.candidates().isEmpty()) {
            source.sendFeedback(() -> Text.translatable("command.florafare.explain.candidates"), false);
            for (FoodBuffManager.Candidate candidate : resolution.candidates()) {
                source.sendFeedback(() -> describeCandidate(candidate), false);
            }
        }

        FoodBuffData data = resolution.data();
        if (data != null) {
            source.sendFeedback(() -> Text.translatable("command.florafare.explain.values",
                    ticksToMmss(data.duration()), data.nutrition(),
                    trim(data.nutrition() * data.saturation() * 2.0),
                    trim(data.healthBonus()),
                    data.attributes().size(), data.effects().size()), false);
            if (data.alwaysEdible()) {
                source.sendFeedback(() -> Text.translatable(
                        "command.florafare.explain.always_edible"), false);
            }
        }
        return 1;
    }

    /** One candidate line: a tick or a cross, the target, its priority, and why it lost. */
    private static Text describeCandidate(FoodBuffManager.Candidate candidate) {
        MutableText line = Text.literal(candidate.chosen() ? " \u2714 " : " \u2718 ")
                .formatted(candidate.chosen() ? Formatting.GREEN : Formatting.DARK_GRAY);
        line.append(Text.literal(candidate.target())
                .formatted(candidate.chosen() ? Formatting.WHITE : Formatting.GRAY));
        line.append(Text.translatable("command.florafare.explain.priority", candidate.priority())
                .formatted(Formatting.DARK_GRAY));
        if (candidate.reason() != null) {
            line.append(Text.literal(" \u2014 ").formatted(Formatting.DARK_GRAY));
            line.append(Text.translatable("command.florafare.explain.reason." + candidate.reason())
                    .formatted(Formatting.DARK_GRAY));
        }
        return line;
    }
}
