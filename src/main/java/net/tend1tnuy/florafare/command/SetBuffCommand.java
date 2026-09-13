package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandRegistryAccess;
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
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.registry.ItemRegistry;

import java.util.ArrayList;
import java.util.List;
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
                                                                        .then(CommandManager.argument("attr_amount", DoubleArgumentType.doubleArg())
                                                                                .then(CommandManager.argument("attr_op", StringArgumentType.word())
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

                        // Branch: /florafare buff give (Grant buff from datapack to player)
                        .then(CommandManager.literal("buff")
                                .then(CommandManager.literal("give")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .then(CommandManager.argument("targetId", StringArgumentType.string())
                                                        .executes(SetBuffCommand::executeBuffGive)
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
        FoodBuffManager.saveRuntimeConfigs();

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
            component.unlockFood(targetId);

            // Actually apply the buff (previously this only unlocked the journal entry).
            // Resolve a display stack for plain item targets so the HUD shows the right icon.
            ItemStack displayStack = Items.APPLE.getDefaultStack();
            if (!targetId.startsWith("#")) {
                Identifier itemId = Identifier.tryParse(targetId);
                if (itemId != null && Registries.ITEM.containsId(itemId)) {
                    displayStack = Registries.ITEM.get(itemId).getDefaultStack();
                }
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
        int maxSlots = PlayerFoodComponent.MAX_BUFF_SLOTS;

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