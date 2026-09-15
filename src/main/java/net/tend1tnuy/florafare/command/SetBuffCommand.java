package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
                                                .then(CommandManager.argument("targetId", StringArgumentType.string())
                                                        .executes(SetBuffCommand::executeBuffGive)
                                                )
                                        )
                                )
                                // Drop one named buff.
                                .then(CommandManager.literal("remove")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .then(CommandManager.argument("targetId", StringArgumentType.string())
                                                        .executes(SetBuffCommand::executeBuffRemove)
                                                )
                                        )
                                )
                        )

                        // Branch: /florafare journal give (Replaces a lost Food Journal)
                        .then(CommandManager.literal("journal")
                                .then(CommandManager.literal("give")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .executes(SetBuffCommand::executeJournalGive)
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
        );
    }

    /**
     * Drops a single active buff, addressed by its config target or by the item that
     * was eaten. Forgotten Mead only removes the newest, so this is the only way to
     * clear one buff out of the middle of a full set.
     */
    private static int executeBuffRemove(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        String rawTarget = StringArgumentType.getString(context, "targetId");
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
        String targetId = FoodBuffManager.normalizeTarget(StringArgumentType.getString(context, "targetId"));

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
}